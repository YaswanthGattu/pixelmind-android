# 📲 How to Get PixelMind APK on Your Phone
## Step-by-step — No Android Studio needed

---

## STEP 1 — Create a GitHub Repository

1. Go to https://github.com/new
2. Name it: `pixelmind-android`
3. Set to **Public**
4. Click **Create repository**

---

## STEP 2 — Upload the Project Files

On the new repo page, click **"uploading an existing file"** link.

Upload ALL files from the `pixelmind-android` folder, keeping the folder structure:

```
.github/workflows/build.yml        ← THE MOST IMPORTANT FILE
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/pixelmind/MainActivity.kt
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/values/colors.xml
app/src/main/res/drawable-v24/ic_launcher_foreground.xml
app/src/main/res/mipmap-hdpi/ic_launcher.xml
app/src/main/res/mipmap-hdpi/ic_launcher_round.xml
app/src/main/res/mipmap-mdpi/ic_launcher.xml
... (all other mipmap files)
app/src/main/res/xml/file_paths.xml
build.gradle.kts
gradle/wrapper/gradle-wrapper.properties
gradle.properties
gradlew
settings.gradle.kts
```

💡 TIP: Drag and drop the entire folder into GitHub's upload page.

Click **Commit changes**

---

## STEP 3 — Watch it Build (Automatically!)

1. Click the **Actions** tab at the top of your repo
2. You'll see "Build PixelMind APK" running (yellow dot = in progress)
3. Wait ~5-8 minutes for it to finish (green checkmark ✅)

If it fails (red ✗), click on it to see the error and share it with me.

---

## STEP 4 — Download Your APK

1. Click the completed workflow run
2. Scroll down to **Artifacts**
3. Click **PixelMind-APK** to download a ZIP file
4. Unzip it — you'll find `app-debug.apk` inside

---

## STEP 5 — Install on Your Phone

**On your Android phone:**

1. Go to **Settings → Apps → Special app access → Install unknown apps**
2. Find your browser (Chrome/Firefox) and enable **"Allow from this source"**
3. Transfer the APK to your phone (email it to yourself, or use Google Drive)
4. Open the APK file on your phone
5. Tap **Install**
6. Open **PixelMind** — it will appear on your home screen!

---

## ✅ That's it!

The app will:
- Ask for photo permission on first launch
- Automatically index all images on your device
- Let you search by meaning: "train", "sunset", "dog", etc.

---

## If GitHub Actions fails

Common fixes:
- Make sure `gradlew` file was uploaded (it's the build script)
- Make sure `.github/workflows/build.yml` is in the right folder
- Share the error message with me and I'll fix it
