package ai.anthracite.fasttrack.ui.nutrition

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.DialogEditFoodItemBinding
import ai.anthracite.fasttrack.databinding.FragmentMealEditorBinding
import ai.anthracite.fasttrack.nutrition.NutritionCalculator
import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.DataQuality
import ai.anthracite.fasttrack.nutrition.model.FoodClassification
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.NutritionEstimate
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import ai.anthracite.fasttrack.nutrition.model.NutritionValue
import ai.anthracite.fasttrack.nutrition.model.Portion
import ai.anthracite.fasttrack.nutrition.model.SizeBasis
import ai.anthracite.fasttrack.nutrition.model.UnitKind
import ai.anthracite.fasttrack.ui.common.ShareJson
import ai.anthracite.fasttrack.ui.common.showScrollable
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MealEditorFragment : Fragment() {

    private var _binding: FragmentMealEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MealEditorViewModel by viewModels()
    private lateinit var adapter: FoodItemAdapter
    private var pendingPhoto: java.io.File? = null

    private val takePicture = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { saved ->
        val file = pendingPhoto
        pendingPhoto = null
        if (saved && file != null) analyzePhoto { MealPhotos.fromFile(file).also { file.delete() } }
    }

    private val pickPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) analyzePhoto { MealPhotos.fromUri(requireContext().contentResolver, uri) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMealEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FoodItemAdapter(onEdit = ::showItemDialog, onDelete = { viewModel.removeItem(it.id) })
        binding.itemList.layoutManager = LinearLayoutManager(requireContext())
        binding.itemList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { onMenu(it.itemId) }

        binding.mealNameInput.doAfterText { viewModel.setMealName(it) }
        binding.mealChips.setOnCheckedStateChangeListener { _, checked ->
            viewModel.setSlot(
                when (checked.firstOrNull()) {
                    R.id.mealBreakfast -> ai.anthracite.fasttrack.nutrition.model.MealSlot.BREAKFAST
                    R.id.mealLunch -> ai.anthracite.fasttrack.nutrition.model.MealSlot.LUNCH
                    R.id.mealDinner -> ai.anthracite.fasttrack.nutrition.model.MealSlot.DINNER
                    R.id.mealSnack -> ai.anthracite.fasttrack.nutrition.model.MealSlot.SNACK
                    else -> ai.anthracite.fasttrack.nutrition.model.MealSlot.UNSPECIFIED
                }
            )
        }
        binding.dateTimeButton.setOnClickListener { pickDateTime() }
        binding.parseButton.setOnClickListener {
            viewModel.parse(binding.describeInput.text?.toString().orEmpty())
        }
        binding.addItemButton.setOnClickListener { showItemDialog(null) }
        binding.saveButton.setOnClickListener { save() }
        binding.photoButton.setOnClickListener { choosePhotoSource() }
        binding.barcodeButton.setOnClickListener { scanBarcode() }

        val entryId = arguments?.getString(ARG_ENTRY_ID)
        val dayMs = arguments?.getLong(ARG_DAY_MS)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (savedInstanceState == null) viewModel.load(entryId, dayMs)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private var suppressNameSync = false

    private fun render(s: MealEditorState) {
        binding.toolbar.menu.findItem(R.id.action_delete_meal)?.isVisible = s.isEditing

        if (binding.mealNameInput.text?.toString() != s.mealName) {
            suppressNameSync = true
            binding.mealNameInput.setText(s.mealName)
            binding.mealNameInput.setSelection(s.mealName.length)
            suppressNameSync = false
        }

        val chipId = when (s.mealSlot) {
            ai.anthracite.fasttrack.nutrition.model.MealSlot.BREAKFAST -> R.id.mealBreakfast
            ai.anthracite.fasttrack.nutrition.model.MealSlot.LUNCH -> R.id.mealLunch
            ai.anthracite.fasttrack.nutrition.model.MealSlot.DINNER -> R.id.mealDinner
            ai.anthracite.fasttrack.nutrition.model.MealSlot.SNACK -> R.id.mealSnack
            else -> View.NO_ID
        }
        if (chipId != View.NO_ID && binding.mealChips.checkedChipId != chipId) {
            binding.mealChips.check(chipId)
        }

        binding.dateTimeButton.text = dateLabel(s.loggedAtMs)

        adapter.submitList(s.items)
        binding.emptyItems.visibility = if (s.items.isEmpty()) View.VISIBLE else View.GONE

        renderTotals(s.items)
        renderParse(s.parse)
    }

    private fun renderTotals(items: List<FoodItem>) {
        val cal = NutritionCalculator.mealCalories(items)
        val est = cal.estimate
        binding.totalCalories.text =
            if (est == null) "— kcal" else "${NutritionCalculator.round0(est)} kcal"
        val parts = mutableListOf<String>()
        val low = NutritionCalculator.round0(cal.low)
        val high = NutritionCalculator.round0(cal.high)
        if (low != null && high != null && low != high) parts += "$low–$high"
        if (cal.totalItems > 0) {
            val coverage = "${cal.knownItems}/${cal.totalItems}"
            parts += when {
                cal.isComplete -> "$coverage estimated"
                cal.hasAnyData -> "$coverage have calories"
                else -> "no calorie data"
            }
        }
        binding.totalSubtitle.text = parts.joinToString(" · ")
    }

    private fun renderParse(status: ParseStatus) {
        when (status) {
            is ParseStatus.Idle -> binding.parseStatus.visibility = View.GONE
            is ParseStatus.Running -> {
                binding.parseStatus.visibility = View.VISIBLE
                binding.parseStatus.text = getString(R.string.meal_editor_parsing)
            }
            is ParseStatus.Done -> {
                binding.parseStatus.visibility = View.VISIBLE
                binding.parseStatus.text =
                    "Added ${status.count} item${if (status.count == 1) "" else "s"} · ${status.engine} · ${status.elapsedMs}ms"
            }
            is ParseStatus.Failed -> {
                binding.parseStatus.visibility = View.VISIBLE
                binding.parseStatus.text =
                    "${getString(R.string.meal_editor_parse_failed)} (${status.message})"
            }
        }
    }

    private fun onMenu(id: Int): Boolean = when (id) {
        R.id.action_load_sample -> { viewModel.loadSample(); true }
        R.id.action_export_json -> { exportJson(); true }
        R.id.action_delete_meal -> { confirmDelete(); true }
        else -> false
    }

    private fun save() {
        if (viewModel.state.value.items.isEmpty()) {
            Snackbar.make(binding.root, R.string.meal_editor_need_items, Snackbar.LENGTH_SHORT).show()
            return
        }
        viewModel.save {
            Snackbar.make(requireActivity().findViewById(android.R.id.content),
                R.string.meal_editor_saved, Snackbar.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.meal_editor_delete_meal)
            .setMessage(R.string.meal_editor_delete_meal)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.delete {
                    Snackbar.make(requireActivity().findViewById(android.R.id.content),
                        R.string.meal_editor_deleted, Snackbar.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun exportJson() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (json, fileName) = viewModel.exportAll()
            ShareJson.share(requireContext(), json, fileName, getString(R.string.meal_editor_export))
        }
    }

    private fun pickDateTime() {
        val cal = Calendar.getInstance().apply { timeInMillis = viewModel.state.value.loggedAtMs }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        val picked = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        viewModel.setDateTime(picked.timeInMillis)
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            // Can't log meals from before the user joined the app.
            val prefs = (requireContext().applicationContext as FastTrackApp).prefs
            datePicker.minDate = ai.anthracite.fasttrack.util.DayKeys.startMs(prefs.joinedDayKey())
        }.show()
    }

    // ---- Per-item edit dialog -----------------------------------------------------------------

    private fun choosePhotoSource() {
        val app = requireActivity().application as FastTrackApp
        val llm = app.onDeviceLlm
        val problem = when {
            !app.prefs.aiFeaturesEnabled -> "Turn on on-device AI in Settings to log meals from photos."
            !llm.model.supportsVision -> "${llm.model.displayName} can't read photos — pick a vision model in Connections."
            !llm.isModelPresent -> "Set up ${llm.model.displayName} in Connections to log meals from photos."
            else -> null
        }
        if (problem != null) {
            Snackbar.make(binding.root, problem, Snackbar.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Log from a photo")
            .setItems(arrayOf("Take a photo", "Choose from gallery")) { _, which ->
                if (which == 0) {
                    val file = MealPhotos.newCaptureFile(requireContext())
                    pendingPhoto = file
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), "${requireContext().packageName}.fileprovider", file
                    )
                    takePicture.launch(uri)
                } else {
                    pickPhoto.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
            }
            .show()
    }

    private fun analyzePhoto(load: () -> ByteArray?) {
        viewModel.setParseRunning()
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching(load).getOrNull() }
            if (bytes == null) {
                viewModel.setParseFailed("Couldn't open that photo")
                return@launch
            }
            viewModel.parsePhoto(bytes, binding.describeInput.text?.toString().orEmpty())
        }
    }

    private fun scanBarcode() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E
            )
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(requireContext(), options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: return@addOnSuccessListener
                lookupBarcode(code)
            }
            .addOnFailureListener { e ->
                if (_binding != null) Snackbar.make(binding.root, "Scanner unavailable: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
    }

    private fun lookupBarcode(code: String) {
        viewModel.setParseRunning()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = ai.anthracite.fasttrack.nutrition.barcode.OpenFoodFacts.lookup(code)) {
                is ai.anthracite.fasttrack.nutrition.barcode.OpenFoodFacts.Lookup.Found -> {
                    viewModel.clearParseStatus()
                    if (_binding == null) return@launch
                    if (!r.perServing) {
                        Snackbar.make(binding.root, "No serving size on the label — values are per 100 g. Adjust the quantity.", Snackbar.LENGTH_LONG).show()
                    }
                    showItemDialog(r.item)
                }
                ai.anthracite.fasttrack.nutrition.barcode.OpenFoodFacts.Lookup.NotFound ->
                    viewModel.setParseFailed("Product $code isn't in Open Food Facts yet — add it manually.")
                is ai.anthracite.fasttrack.nutrition.barcode.OpenFoodFacts.Lookup.Failed ->
                    viewModel.setParseFailed(r.message)
            }
        }
    }

    private fun showItemDialog(existing: FoodItem?) {
        val d = DialogEditFoodItemBinding.inflate(layoutInflater)

        val unitAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            FoodLabels.unitOptions.map { FoodLabels.unitOptionLabel(it) }
        )
        d.unitInput.setAdapter(unitAdapter)
        var selectedUnit = existing?.unit ?: MeasurementUnit.SERVING
        d.unitInput.setText(FoodLabels.unitOptionLabel(selectedUnit), false)
        d.unitInput.setOnItemClickListener { _, _, pos, _ -> selectedUnit = FoodLabels.unitOptions[pos] }

        if (existing != null) {
            d.nameInput.setText(existing.name)
            d.quantityInput.setText(FoodLabels.quantity(existing.quantity))
            existing.nutrition.caloriesKcal.estimate?.let { d.calInput.setText(trimNum(it)) }
            existing.nutrition.caloriesKcal.low?.let { d.calLowInput.setText(trimNum(it)) }
            existing.nutrition.caloriesKcal.high?.let { d.calHighInput.setText(trimNum(it)) }
            existing.nutrition.proteinGrams?.estimate?.let { d.proteinInput.setText(trimNum(it)) }
            existing.nutrition.carbohydratesGrams?.estimate?.let { d.carbsInput.setText(trimNum(it)) }
            existing.nutrition.fatGrams?.estimate?.let { d.fatInput.setText(trimNum(it)) }
            existing.nutrition.alcoholGrams?.estimate?.let { d.alcoholInput.setText(trimNum(it)) }
            d.alcoholicCheck.isChecked = existing.classification.isAlcoholic
            d.verifiedCheck.isChecked = existing.nutrition.caloriesKcal.verified
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.meal_item_new else R.string.meal_item_edit)
            .setView(d.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                buildItem(existing, d, selectedUnit)?.let { viewModel.upsertItem(it) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showScrollable(d.root)
    }

    private fun buildItem(
        existing: FoodItem?,
        d: DialogEditFoodItemBinding,
        unit: MeasurementUnit
    ): FoodItem? {
        val name = d.nameInput.text?.toString()?.trim().orEmpty().ifBlank { "Item" }
        val quantity = d.quantityInput.text?.toString()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0
        val verified = d.verifiedCheck.isChecked
        val alcoholic = d.alcoholicCheck.isChecked

        val source = if (verified) NutritionSource.USER_INPUT
        else existing?.nutrition?.caloriesKcal?.source ?: NutritionSource.USER_INPUT
        val confidence = if (verified) Confidence.HIGH
        else existing?.nutrition?.caloriesKcal?.confidence ?: Confidence.MEDIUM

        val calories = calorieValue(d, source, confidence, verified)
        val protein = macroValue(d.proteinInput.text?.toString(), source, confidence, verified)
        val carbs = macroValue(d.carbsInput.text?.toString(), source, confidence, verified)
        val fat = macroValue(d.fatInput.text?.toString(), source, confidence, verified)
        val alcohol = macroValue(d.alcoholInput.text?.toString(), source, confidence, verified)

        val isLiquid = existing?.classification?.isLiquid ?: (unit.kind == UnitKind.VOLUME)
        val category = when {
            alcoholic || isLiquid -> ItemCategory.BEVERAGE
            else -> existing?.category ?: ItemCategory.FOOD
        }

        return FoodItem(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            canonicalName = existing?.canonicalName?.takeIf { it.isNotBlank() }
                ?: name.lowercase(Locale.US).replace(' ', '_'),
            category = category,
            subcategory = existing?.subcategory.orEmpty(),
            foodType = existing?.foodType.orEmpty(),
            quantity = quantity,
            unit = unit,
            portion = Portion(
                amount = existing?.portion?.amount ?: 1.0,
                unit = unit,
                sizeBasis = existing?.portion?.sizeBasis ?: SizeBasis.CUSTOM,
                assumed = existing?.portion?.assumed ?: !verified
            ),
            preparation = existing?.preparation,
            ingredients = existing?.ingredients,
            nutrition = NutritionEstimate(
                caloriesKcal = calories,
                proteinGrams = protein,
                carbohydratesGrams = carbs,
                fatGrams = fat,
                alcoholGrams = alcohol
            ),
            classification = (existing?.classification ?: FoodClassification()).copy(
                isAlcoholic = alcoholic,
                isLiquid = isLiquid
            ),
            dataQuality = DataQuality(source = source, confidence = confidence, verified = verified)
        )
    }

    private fun calorieValue(
        d: DialogEditFoodItemBinding,
        source: NutritionSource,
        confidence: Confidence,
        verified: Boolean
    ): NutritionValue {
        val est = d.calInput.text?.toString()?.toDoubleOrNull()
            ?: return NutritionValue(source = source, confidence = confidence, verified = verified)
        var low = d.calLowInput.text?.toString()?.toDoubleOrNull()
        var high = d.calHighInput.text?.toString()?.toDoubleOrNull()
        // Sanitize so low <= est <= high; drop bounds that violate ordering.
        if (low != null && low > est) low = null
        if (high != null && high < est) high = null
        return NutritionValue(est, low, high, source, confidence, verified)
    }

    private fun macroValue(
        raw: String?,
        source: NutritionSource,
        confidence: Confidence,
        verified: Boolean
    ): NutritionValue? {
        val v = raw?.toDoubleOrNull() ?: return null
        return NutritionValue(v, null, null, source, confidence, verified)
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun dateLabel(ms: Long): String {
        val today = Calendar.getInstance()
        val that = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = today.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
        return if (sameDay) "Today, $time"
        else SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(ms)) + ", $time"
    }

    private fun com.google.android.material.textfield.TextInputEditText.doAfterText(
        block: (String) -> Unit
    ) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!suppressNameSync) block(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_ENTRY_ID = "entryId"
        const val ARG_DAY_MS = "dayMs"
    }
}
