# SM De-noise

Personal Android app: sends you Back when Instagram/Facebook Reels or YouTube Shorts open, and caps TikTok to a daily time budget.

A video someone sent you still plays (an Instagram reel opened from a DM, a YouTube Short opened from a link in another app), but swiping to the next one sends you Back.

Instagram profiles (including their Reels tab) stay open. Reels by accounts listed under "Always allow reels by" (e.g. your own) play, and you can swipe between them.

## Install
1. Install Android Studio. File > Open > select this folder, let Gradle sync.
2. On your phone: Settings > About phone > tap Build number 7 times > Developer options > enable USB debugging. Plug in via USB.
3. Pick your phone in the device dropdown, press Run.
4. Open SM De-noise > "Open accessibility settings" > Installed apps > SM De-noise > turn on.
   - If the toggle is greyed out ("Restricted setting"): Settings > Apps > SM De-noise > ⋮ menu > Allow restricted settings.

## If blocking stops working after an app update
Instagram/Facebook/YouTube rename internal view ids. Turn on Debug logging in the app, open the Reels/Shorts screen, run `adb logcat -s SocialGuard`, and add the new ids to the lists at the top of `BlockerService.kt`.
