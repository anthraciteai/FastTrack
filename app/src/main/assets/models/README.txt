On-device food-log model (bundled)
==================================

Drop the LiteRT-LM model weights here to ship the app WITH the model, so it auto-installs
on first launch — no download and no user action required.

Default model (see LlmModel.DEFAULT): Gemma 3 1B (.litertlm format)
Expected filename:                    gemma3-1b-it.litertlm

    app/src/main/assets/models/gemma3-1b-it.litertlm

Get the weights from the HuggingFace LiteRT Community (e.g. litert-community/Gemma3-1B-IT)
and rename the downloaded .litertlm file to match the name above.

On first launch, FastTrackApp copies this into internal storage
(files/models/gemma3-1b-it.litertlm) and the food-log estimator uses it automatically.
If this file is absent, the app falls back to the built-in offline estimator (and, if a
MEAL_MODEL_URL secret is set, can download the weights instead).

Notes
-----
- Gemma weights are license-gated (Hugging Face / Kaggle). Obtain the .litertlm yourself and
  place it here; it is intentionally NOT committed to git (see .gitignore).
- The .litertlm extension is kept uncompressed in the APK (see androidResources.noCompress).
- For Play Store distribution a ~1 GB asset is too large for the base APK — use Play Asset
  Delivery (an install-time asset pack) instead of this folder.
