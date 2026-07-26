# Save2Downloads

A minimal Android app that appears in the system Share Sheet and saves any shared file directly to your Downloads folder.

## How to build (GitHub Actions — no Android Studio needed!)

1. **Create a new GitHub repo** (e.g., `Save2Downloads`)
2. **Upload all these files** to the repo (drag & drop the whole folder, or push via git)
3. **Go to Actions → Build APK** in your GitHub repo
4. **Click "Run workflow"** (or it auto-runs on push)
5. **Wait ~3 minutes**
6. **Download the APK** from the Artifacts section
7. Transfer to your phone and install

## How to use

1. In Kimi (or any app), tap a file link
2. When the Share Sheet opens, scroll and select **Save2Downloads**
3. The file is saved to `Downloads/` instantly
4. A toast confirms the filename

## Notes

- Android 10+ (API 29+): No storage permission needed — uses MediaStore
- Android 9 and below: Needs WRITE_EXTERNAL_STORAGE permission (declared in manifest)
- The app has no UI — it saves and exits immediately
