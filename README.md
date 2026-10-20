# HD Downloader

Facebook aur YouTube videos ko original / HD quality mein download karne wali Android app.

- Link paste karein (ya YouTube/Facebook app se **Share > HD Downloader**)
- Video ka **title, thumbnail aur preview player** dikhta hai
- Available qualities ki list (Best/original, 4K, 2K, 1080p, 720p ... + Audio MP3)
- Fast download (parallel fragments), background mein bhi chalta hai (notification ke saath)
- Video Gallery mein `Movies/HD Downloader` folder mein save hoti hai

## GitHub par APK banane ka tareeqa

1. GitHub par naya repository banayein (e.g. `HDDownloader`).
2. Is poore folder ki files us repo mein upload/push karein (`.github` folder zaroor shamil ho).
3. Repo ke **Actions** tab mein jayein. `Build APK` workflow khud chalega (ya **Run workflow** dabayein).
4. 5-10 minute baad workflow ke page par neeche **Artifacts** mein APK milegi:
   - `HDDownloader-universal` : har phone par chalti hai (bari file)
   - `HDDownloader-arm64` : zyada tar naye phones ke liye (chhoti file)
5. Zip download karein, APK phone mein install karein (Unknown sources allow karna hoga).

## Zaroori

- YouTube/Facebook aksar badalte rehte hain. Agar download band ho jaye to app mein **Update engine** dabayein.
- Sirf wahi videos download karein jin ki aap ko ijazat ho. YouTube ki Terms of Service download ki ijazat nahi deti.
- App ka naam badalna ho to `app/src/main/res/values/strings.xml` mein `app_name` badlein.
