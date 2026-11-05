# PDF Editable

A small Android app for viewing PDFs, typing text onto them, and signing them with your
finger or a stylus. Edits are written into a real PDF, so the result opens anywhere.

## Features

- **View** any PDF from the Files picker, or open one from another app (mail, file manager).
  Pages render with the platform `PdfRenderer`, pinch or use the zoom buttons.
- **Add text**: pick the Text tool, tap where the text should go, type it, choose a size.
  Text is embedded as real PDF text (selectable and searchable in other viewers).
- **Sign**: pick the Sign tool, tap where the signature belongs, draw it on the pad.
  The signature is stored as a transparent image. Reuse the previous signature on other pages.
- Tap an item to select it; drag to move; use the selection bar to resize, edit or delete.
- Undo, "Save" back to the original file, or "Save as…" to a new file.

## Project layout

```
app/src/main/java/com/pdfeditable/
├── MainActivity.kt            Entry point, file picker and save-as launchers, ACTION_VIEW handling
├── EditorViewModel.kt         Document state, annotations, undo stack, open/save
├── model/Annotations.kt       TextAnnotation, SignatureAnnotation, Signature strokes
├── pdf/PdfPageRenderer.kt     Renders pages to bitmaps with android.graphics.pdf.PdfRenderer
├── pdf/PdfExporter.kt         Burns annotations into the PDF with PdfBox-Android
└── ui/
    ├── EditorScreen.kt        Scaffold, toolbars, page list, dialogs wiring
    ├── PdfPage.kt             One page: bitmap, annotation overlay, tap/drag/pinch gestures
    ├── TextDialog.kt          Add / edit text
    ├── SignaturePadDialog.kt  Draw a signature
    └── theme/Theme.kt
```

### How coordinates work

Annotations are stored in PDF points in "visual" space: origin at the top-left of the page as
displayed, y downwards, page rotation already applied. That is exactly what `PdfRenderer`
shows, so hit-testing on screen is a simple scale. When saving, `PdfExporter` applies one
transformation matrix per page that accounts for the page's `/Rotate` entry and crop box
offset, then draws text and images in that space.

Saving produces a flattened copy. After a successful save the saved file becomes the new
base document, so saving twice never stamps the same annotation twice.

## Building

Requirements: Android Studio Ladybug (2024.2) or newer, or JDK 17+ with the Android SDK
(platform 34) installed.

```bash
./gradlew assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`. Install it with:

```bash
./gradlew installDebug
```

Minimum Android version is 8.0 (API 26).

### Getting an APK from GitHub Actions

Every push runs the **Build APK** workflow (`.github/workflows/build.yml`), which runs the unit
tests, builds a debug APK and uploads it as an artifact. You can also start it by hand from the
Actions tab with **Run workflow**.

To install it on a phone:

1. On GitHub, open **Actions**, pick the latest green **Build APK** run, and download the
   `pdf-editable-debug-apk` artifact under **Artifacts**. You must be signed in to GitHub.
2. GitHub always delivers artifacts as a `.zip`. Unzip it to get `pdf-editable-<commit>.apk`.
   On a phone, the Files app can extract it: tap the zip, then **Extract**.
3. Tap the `.apk`. Android asks you to allow installs from that app (Files or Chrome) the first
   time; allow it, go back, and tap **Install**.

The debug build is signed with a debug key, so Play Protect may warn that the app is from an
unknown developer. Choose **Install anyway**. The debug key lives in `app/debug.keystore` and
uses Android's standard public debug passwords, so it is not a secret. Because every build uses
the same key, a newer APK installs straight over an older one and keeps your data.

## Dependencies

- Jetpack Compose (Material 3) for the UI.
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) for writing text
  and images into the PDF when saving. Rendering uses the platform `PdfRenderer`, so there is
  no native PDF library to ship.

## Known limitations

- Text is written in Helvetica with WinAnsi encoding; characters outside that set (emoji,
  most non-Latin scripts) are replaced with `?` when saving.
- Password-protected PDFs cannot be opened.
- Existing form fields and annotations are left untouched; the app only adds new content.
