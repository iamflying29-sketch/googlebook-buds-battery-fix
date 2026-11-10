# Buds Battery Fix

Buds Battery Fix keeps the left and right battery levels of my Pixel Buds 2a showing on my Lenovo
Googlebook 15 and my Lenovo Tab P12. Google's Fast Pair sometimes stores both earbuds as "unknown"
while the charging case has a real level, so Google's Batteries widget and the Bluetooth settings
show only the case. Buds Battery Fix notices that moment within a fraction of a second and puts back
the latest real levels that the earbuds themselves reported. Every new report from the earbuds passes
straight through, so the levels stay current. It never makes up a number.

It runs on each device itself as a small helper, with no Linux Terminal involved, and a small native
Android app starts it again after every restart.

## What it does

* It watches the battery values that Android stores for each paired Fast Pair true wireless headset:
  left, right and case, each with a level and a charging flag.
* When Fast Pair stores the one bad combination, both earbuds "unknown" and "charging" while the case
  has a real level, it writes back the earbuds' latest real levels and charging states. The new case
  level that Fast Pair just stored is kept.
* It only uses a reading that it saw arrive itself, and only if that reading is less than 10 minutes
  old or the earbuds have stayed connected ever since. Otherwise it leaves the values alone.
* Every other change passes through untouched, including real reports and other "unknown" values,
  for example when the case is out of reach.
* It writes a line to its log for each fix, and the app shows how many fixes it made this month and
  when the last one was.

## The problem

The Fast Pair pop-up and the Batteries widget sometimes showed only my charging case, for example
"Case 97%", with no left or right earbud. It happened on both of my devices.

To find out why, I recorded everything Pixel Buds related on my Googlebook for 11 days with an
on-device recorder I wrote for this, taking 3,469 snapshots of the stored battery values. What it
showed:

* In 183 snapshots the Fast Pair pop-up showed only the case.
* In 198 snapshots the stored values were "left unknown, charging", "right unknown, charging" and a
  real case level. I believe this comes from a battery byte of 0xFF, which means "charging" plus
  "level unknown", but I could not see the raw values, because Google Play services hides them in
  its log.
* 197 of those 198 bad values were written by Fast Pair's advertisement path. Its log lines say
  "battery advertisement ... has battery values updated". For the remaining one, the last write before
  it came from the earbuds' direct Fast Pair connection to the device.
* At each of those moments, the last real reading of both earbuds was a median of about 2 minutes
  old, and the earbuds kept sending real readings afterwards.

So the earbuds keep reporting real levels, but now and then a battery advertisement arrives that
marks both earbuds as unknown, and Fast Pair stores it as it is. I could not tell whether the earbuds
send it that way or Play services reads it that way.

## How I solved it

### The stored values can be corrected safely

The adb shell user has Android's Bluetooth privileges, so it can read the stored battery values,
write them, and register to be told the moment they change. I checked reading, writing and listening
on both devices. On the Googlebook I also changed the stored left level by one percent and put it back
right away, and both writes were reported to the listener within milliseconds.

### The Batteries widget reads exactly these values

I traced where Google's Batteries widget gets its numbers. The widget asks the Settings app for the
Bluetooth battery data, and Settings answers with `device.getMetadata()` for the left, right and case
levels and charging flags. Those are the values this helper corrects. In my logs the widget asked
about one second after Fast Pair's update, and the helper finishes well before that.

### The helper

The helper is written in Kotlin and runs as the adb shell user through `app_process`, with its code
taken from the installed Buds Battery Fix app. It:

* listens for changes to the stored values of every paired Fast Pair true wireless headset;
* waits 30 milliseconds after a change, because Fast Pair writes the four earbud values one after
  another within about 15 milliseconds, and then looks at the whole picture;
* remembers each real reading Fast Pair stores while it is watching, and never trusts values that
  were already stored when it started, because their age is unknown;
* checks every 30 seconds whether each headset is still connected, so it knows whether a reading can
  still be current;
* exits if a Bluetooth call does not return within 20 seconds, or if Bluetooth is turned off, so a
  fresh helper can start cleanly.

A supervisor script, `budsfix.sh`, starts the helper and starts it again if it ever exits, after
5 seconds, and with longer pauses if it keeps failing.

### The app

The Buds Battery Fix app is a native Android app written in Kotlin. It has its own paired Wireless
debugging key, separate from every other tool. At every restart, and every 15 minutes, it connects
to the device's own Wireless debugging service, proves it is talking to Android's adb service,
installs the bundled supervisor script if it changed, and makes sure the fix is running. After an
app update it restarts the helper with the new code. If the fix has not been running for an hour, it
shows a notification.

### Tests

* **Imitation test, on both devices:** a test tool first stored a real reading of 97% left and 96%
  right, then the bad combination with the case at 100%. On the Googlebook the helper put back 97% and
  96% about 40 milliseconds after the bad values were written. On the Tab P12 it took about a tenth of
  a second. The original values were restored afterwards.
* **Restarts:** I killed the running helper and the supervisor started a new one 5 seconds later.
  After an app update, the app restarted the helper with the new code by itself.
* **Independence:** I disconnected my computer from the Tab P12 completely for 45 seconds. The same
  supervisor and helper kept running.

At the time of publishing, I have not yet seen the fix catch a real bad advertisement, because the
tests imitated Fast Pair's writes. The log will show it when it does.

## Requirements

* A device with Google Play services and Fast Pair, running Android 14 or newer. I use it on my
  Lenovo Googlebook 15 with GooglebookOS (Android 17) and on my Lenovo Tab P12 with Android 15. I have
  not tested Android 14.
* Fast Pair earbuds that report left, right and case levels. I have only tested Pixel Buds 2a.
* The Linux Terminal with adb, a JDK and the Android SDK build tools 35.0.0 with the android-35
  platform, and a paired Wireless debugging connection to each device. The shared
  [Getting Started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md)
  covers all of these, and also explains how to find your Android user ID and why you should turn on
  **Disable adb authorization timeout**.
* JetBrains' Kotlin command-line compiler, which the Getting Started guide does not cover. Download
  `kotlin-compiler-<version>.zip` from the
  [Kotlin releases page](https://github.com/JetBrains/kotlin/releases) and unpack it so that
  `~/tools/kotlin/kotlinc/bin/kotlinc` exists:

  ```bash
  mkdir -p ~/tools/kotlin
  unzip ~/Downloads/kotlin-compiler-*.zip -d ~/tools/kotlin
  ~/tools/kotlin/kotlinc/bin/kotlinc -version
  ```

  If you keep the compiler somewhere else, set `KOTLIN_HOME` to its `kotlinc` folder. I build with
  version 2.4.21.
* `openssl`, to turn the app's key into the format the app reads: `sudo apt install openssl`.

## Installation

These steps set up one device. To set up a second device, repeat steps 2 to 6 for it with its own key
folder, and add `-s <DEVICE_IP>:<PORT>` to each `adb` command so it goes to the right device.

### Step 1. Build the app

```bash
git clone https://github.com/iamflying29-sketch/googlebook-buds-battery-fix.git
cd googlebook-buds-battery-fix/app
KS_PASS='<your keystore password>' ./build.sh
```

The first build creates `keystore.jks` with your password. Keep both, because every later version
must be signed with the same keystore to install as an update.

### Step 2. Install it and give it what it needs

Use your Android user ID from the Getting Started guide. On my Googlebook it is 10, and on my
Tab P12 it is 0.

```bash
adb install --user <USER_ID> BudsBatteryFix.apk

# notifications, for the "not running" warning
adb shell pm grant --user <USER_ID> local.budsfix android.permission.POST_NOTIFICATIONS

# access to the device's own Wireless debugging service, needed on GooglebookOS (Android 17);
# Android 15 has no such permission, and the app works there without it
adb shell pm grant --user <USER_ID> local.budsfix android.permission.ACCESS_LOCAL_NETWORK

# battery: let its 15 minute check run, and never revoke its permissions automatically
adb shell cmd deviceidle whitelist +local.budsfix
adb shell appops set --user <USER_ID> local.budsfix RUN_ANY_IN_BACKGROUND allow
adb shell appops set --user <USER_ID> local.budsfix AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
```

### Step 3. Create the app's own key

```bash
C=~/.config/buds-fix/googlebook
mkdir -p $C/adbhome/.android && chmod 700 $C $C/adbhome $C/adbhome/.android
cd $C/adbhome/.android && adb keygen adbkey
# give the key a name you will recognize in the Paired devices list
printf '%s BudsBatteryFix@googlebook\n' "$(awk '{print $1}' adbkey.pub)" > adbkey.pub
head -c 24 /dev/urandom | base64 | tr -d '/+=' > $C/p12.pass; chmod 600 $C/p12.pass
openssl req -new -x509 -key adbkey -out $C/cert.pem -days 36500 -subj /CN=BudsBatteryFix
openssl pkcs12 -export -inkey adbkey -in $C/cert.pem -out $C/key.p12 -passout "pass:$(cat $C/p12.pass)"
chmod 600 $C/cert.pem $C/key.p12
```

### Step 4. Pair the key with the device

On the device, open **Settings > System > Developer options > Wireless debugging**, tap **Pair device
with pairing code**, and leave that window open. Use the code and the **IP address & Port** shown in
that window. The pairing port is different from the normal one.

```bash
HOME=$C/adbhome adb -P 5045 pair <DEVICE_IP>:<PAIRING_PORT> <PAIRING_CODE>
```

It answers "Successfully paired". The key then appears as **BudsBatteryFix@googlebook** under
**Paired devices**. The separate adb server on port 5045 keeps this key apart from your own adb key.

### Step 5. Hand the key to the app and start the fix

```bash
adb shell am broadcast --user <USER_ID> -n local.budsfix/.ControlReceiver -a local.budsfix.PROVISION \
    --es p12 "$(base64 -w0 $C/key.p12)" --es pass "$(cat $C/p12.pass)"
adb shell am broadcast --user <USER_ID> -n local.budsfix/.ControlReceiver -a local.budsfix.CHECK
```

The first command must answer `data="provisioned"`, and the second `data="scheduled"`. Only the adb
shell or the system can send these, because the receiver requires a system permission.

### Step 6. Check that it runs

```bash
adb shell sh /data/local/tmp/budsfix/budsfix.sh status
```

After a few seconds the first line says `running`, and the log at the bottom shows the helper
starting and a line like this for your earbuds:

```
watching Pixel Buds 2a (XX:XX): listener registered; stored now L=100/true R=100/true C=100/false
```

## Using it

There is nothing to do. Open the **Buds Battery Fix** app to see:

* **Fixing** in green when the fix is running, or **Not running** in red;
* **Fixes this month** and **Last fix**;
* **Last check by this app**, and a **Check now** button that runs the same check as the 15 minute job.

Closing the app's window never affects the fix.

Each fix adds a line like this to the log in `/data/local/tmp/budsfix/fix-<year>-<month>.log`:

```
fixed: Fast Pair stored the buds as unknown with the case at 100%; put back the buds' latest real reading L=97% R=96% (charging false/false), reported 1 s ago
```

### Commands

Run these with `adb shell sh /data/local/tmp/budsfix/budsfix.sh <command>`:

* `status`: whether it runs, the fixes this month, the last fix, and the end of the log;
* `stop`: stops the fix; it stays stopped, even after restarts, until `start`;
* `start`: starts it again after a stop.

## Updating

Build the new version and install it over the old one with `adb install -r --user <USER_ID>
BudsBatteryFix.apk`. The app notices its own update, copies a changed supervisor script to the device
and restarts the helper with the new code. I tested this on my Googlebook.

## Uninstalling

```bash
adb shell sh /data/local/tmp/budsfix/budsfix.sh stop
adb uninstall --user <USER_ID> local.budsfix
```

Then remove **BudsBatteryFix@googlebook** under **Paired devices** on the Wireless debugging screen.
The folder `/data/local/tmp/budsfix` with the log stays on the device until you remove it yourself
with `adb shell rm -r /data/local/tmp/budsfix`.

## Troubleshooting

* **The app says Not running, or you get the "Buds Battery Fix is not running" notification.** Make
  sure Wireless debugging is on, then tap **Check now**. If the app says it has no adb key, repeat
  step 5. If Wireless debugging cannot be reached, check that **Disable adb authorization timeout** is
  on; otherwise Android drops the app's pairing after 7 days without use, and you need to repeat
  steps 4 and 5.
* **The status says the supervisor is waiting.** It cannot find the app's code at that moment, for
  example during an update. It tries again every 30 seconds.
* **The log says "no real bud reading is known yet; left as is".** The helper only uses readings it saw
  arrive itself. Right after it starts, it has none until the earbuds report once.
* **The log says the last real reading is too old.** The reading was more than 10 minutes old and the
  earbuds disconnected in between, so the helper did not put it back.
* **Your earbuds are not watched.** The helper only watches paired devices that Fast Pair marks as true
  wireless headsets with left, right and case levels.

## Safety and privacy notes

* The helper holds no wake lock, changes no setting and uses no network. It only writes the four
  earbud values, left and right level and charging, of paired Fast Pair headsets, and only in the one
  bad combination described above.
* It never makes up a value. It only writes back a reading that the earbuds reported shortly before.
* Every Bluetooth call is watched, and a call that hangs for 20 seconds ends the helper so the
  supervisor can start a clean one.
* The supervisor and the helper only ever stop their own processes, which they find by their exact
  command line.
* Nothing is ever deleted. Each month gets its own log file.
* The app's control receiver accepts commands only from the adb shell or the system, and the app proves
  that its connection reaches Android's adb service before it sends anything.
* Wireless debugging gives a paired computer a lot of control over your device. Only pair computers
  and keys you own.

## Limitations

* **The Fast Pair pop-up is Google Play services' own notification.** I have not verified whether it
  follows the corrected values or keeps showing only the case until the earbuds' next real report.
* The helper puts back the latest real reading, which was a median of about 2 minutes old at the bad
  moments in my 11 days of data, because that is when the earbuds last reported. It never puts back a
  reading that is more than 10 minutes old, unless the earbuds stayed connected the whole time.
* It only fixes the one bad combination. Other "unknown" values, such as the case being out of reach,
  are left as Fast Pair stored them.
* Lenovo's own battery widget on the Tab P12 shows earbuds as one combined level from a different
  source, so this fix does not change it.
* I have tested it only with Pixel Buds 2a, on GooglebookOS (Android 17) and Android 15.
* At the time of publishing, the fix has been proven with an imitation of Fast Pair's writes, and I have
  not yet seen it catch a real bad advertisement. A full device restart with this app is not tested yet;
  it uses the same restart setup as my other on-device helpers.

## Files

* `app/` is the Buds Battery Fix app:
  * `src/kotlin/local/budsfix/Helper.kt` is the helper that watches and corrects the stored values;
  * `Fixer.kt` installs the supervisor script and keeps the fix running;
  * `Adb.kt` and `AdbClient.kt` are the app's own connection to the device's Wireless debugging;
  * `EnsureJob.kt` and `Receivers.kt` are the checks at every restart and every 15 minutes, and the
    control receiver;
  * `Status.kt` remembers the last check and shows the "not running" notification;
  * `MainActivity.kt` is the app window;
  * `build.sh` builds and signs the APK.
* `device/budsfix.sh` is the supervisor that runs on the device. The app bundles it and installs it.

## Related projects

* [Battery Widget](https://github.com/iamflying29-sketch/googlebook-battery-widget) makes Google's
  Batteries widget on the Googlebook show connected Bluetooth earbuds at all.
* [Googlebook Projects](https://github.com/iamflying29-sketch/googlebook-projects) lists all of my
  Googlebook projects and has the Getting Started guide.

## Disclaimer and license

This is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by
Google or Lenovo. All product names belong to their owners. Released under the MIT License.
Copyright (c) 2026 Thomas Liviakis.
