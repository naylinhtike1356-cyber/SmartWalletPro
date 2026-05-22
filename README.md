# Smart Wallet Pro

Smart Wallet Pro is an Android wallet tracker built with a native WebView shell and an offline-first HTML/JavaScript interface.

## Highlights

- Monthly budget tracking with income, expense, and net quick summary
- Account balance tracking across KRW, MMK, and USD
- SMS transaction import support
- Receipt OCR amount capture
- JSON backup/restore and Excel export
- Optional Google Drive backup/restore
- Static Vercel download page for APK distribution

## Build

```powershell
.\gradlew.bat assembleDebug
```

The generated debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Deploy Download Page

The Vercel site serves the files in `public/`, including:

```text
public/downloads/SmartWalletPro.apk
```

