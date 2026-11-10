# Buds Battery Fix

Buds Battery Fix keeps the left and right battery levels of my Pixel Buds 2a showing on my Lenovo
Googlebook 15 and my Lenovo Tab P12. Google's Fast Pair sometimes stores both earbuds as "unknown"
while the charging case has a real level, so Google's Batteries widget and the Bluetooth settings
show only the case. Buds Battery Fix notices that moment within a fraction of a second and puts back
the latest real levels that the earbuds themselves reported. Every new report from the earbuds passes
straight through, so the levels stay current. It never makes up a number.

It also makes sure my Pixel Buds keep connecting by themselves over LE Audio after a restart. On my
Googlebook, Bluetooth's start at boot can take the earbuds off LE Audio's auto-connect list, so they
no longer connect when I take them out of the case. Once after every Bluetooth start, Buds Battery
Fix checks that list and puts them back on it if they are missing.

It is built to survive system updates. After every start, and after every GooglebookOS, Bluetooth or
Play services update, it checks that everything it relies on still works. If something stopped
working, the app says "Needs attention" with the reason and shows a notification, so it can never
break silently.

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
* Once after every Bluetooth start, it looks in Bluetooth's own log to see whether the Pixel Buds are
  on LE Audio's auto-connect list. If they are, or if they are connected, it changes nothing. If not,
  and LE Audio is allowed for them, it re-applies their LE Audio setting, which puts them back on the
  list, and 35 seconds later it confirms the result. It never touches Classic Bluetooth audio, and on
  a device that does not use LE Audio it does nothing.
* After every start it runs a self-check, records the system build and the Bluetooth and Play
  services versions, and logs which of them an update changed. The app shows "Needs attention" and a
  notification if any check fails.

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

### The earbuds sometimes stopped connecting by themselves after a restart

My Googlebook plays audio to my Pixel Buds only over LE Audio, and Classic Bluetooth audio is
switched off for them. Usually they connect the moment I take them out of the case. After some
restarts they did not. I restarted with the earbuds in the closed case and saved the boot log before
taking them out. It showed this:

1. At boot, Bluetooth first starts for Android's system user. That user's LE Audio setting for my
   earbuds is "forbidden", so it switched LE Audio off for them, which also took them off LE Audio's
   auto-connect list, and that was saved.
2. Bluetooth then restarted for my own Android user and loaded the earbuds with auto-connect off.
3. Its own step that switches LE Audio back on for every allowed device ran 2 milliseconds before the
   earbuds were loaded, so it found no devices and switched nothing back on.

When I took the earbuds out, they called the Googlebook over Classic Bluetooth, the Googlebook
refused Classic audio as it should, LE Audio did not pick them up, and the earbuds hung up after
about 4 seconds. Because step 3 is a race, I believe this happens after some restarts and not after
others. I have not verified that.

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

### Putting the earbuds back on LE Audio's auto-connect list

In the Bluetooth service on my Googlebook, setting a device's LE Audio connection policy to "allowed"
does two things: it switches LE Audio on for the device, which puts it back on the auto-connect list,
and it asks LE Audio to connect it. It is the same call that the LE Audio switch in Settings makes,
and the adb shell user may make it.

The helper notices each new start of the Bluetooth service, by its process ID and start time. Once
the service has run for 10 seconds, so LE Audio has loaded the paired earbuds, the helper reads the
Pixel Buds' LE Audio setting in the current Android user. If it is not "allowed", the helper leaves
it that way. If the earbuds are connected or connecting, it changes nothing.

Otherwise it reads that Bluetooth process's own log lines about the earbuds' LE Audio connection
state. When the latest one says they are on the auto-connect list, it changes nothing. When there is
none, which is what the failed restart looked like, it sets "allowed" again. The value stays the
same, so no setting changes. 35 seconds later, after LE Audio's 30 second connection request has had
time to finish, it reads the log again and confirms that the earbuds are connected or back on the
list. It does all this once per Bluetooth start, so a later restart of the helper itself, for example
after an app update, does not repeat it.

The log is read in a bounded way: only the Bluetooth process's lines about these earbuds, read while
they arrive, with a 10 second deadline and a 256 KB cap, after which the reader closes the pipe and
stops the command. If the log cannot be read, or an update changed its wording, the helper does not
guess. It sets "allowed" again anyway, which I tested and found harmless both when the earbuds were
missing from the list and when they were on it.

### Staying working through updates

An update of GooglebookOS, the Bluetooth module or Play services could change any of the system
parts this fix uses. So at every start the helper checks each of them and writes the result to
`/data/local/tmp/budsfix/health.txt`, one line per item, marked `ok`, `info` or `attention`:

* that it could open Bluetooth as the adb shell user, and which of its four ways worked;
* that the adb shell user still has Android's Bluetooth privileges, which the battery fix needs;
* that each pair of Pixel Buds is marked by Fast Pair as true wireless earbuds and that Android
  accepted the listener for their battery values;
* whenever it writes a correction, that Android accepted every value;
* that it can find Bluetooth's process and that LE Audio answers. It also looks for a Bluetooth
  process whose name an update changed, by its name pattern and Bluetooth's user ID, but I could not
  test that case;
* the outcome of the LE Audio step and its confirmation;
* the system build and the Bluetooth and Play services versions.

It keeps the versions in `versions.txt`. When an update changed one of them, the log says which one,
from which version to which, and the self-check runs right after. `budsfix.sh status` shows every
`attention` line and a summary. The app reads those lines at every check. If there are any, its
window says **Needs attention** with the reasons and it shows a notification, which goes away by
itself at the first check that finds everything working again.

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
* **Real restarts:** after I restarted my Googlebook, the app started the fix by itself, 16 seconds
  after Bluetooth came up, without the Linux Terminal or the app's window. I restarted again with the
  LE Audio step installed and the earbuds in the closed case. On that restart Bluetooth put the
  earbuds back on the auto-connect list by itself, the step ran 13 seconds after Bluetooth started,
  and when I took the earbuds out they connected by themselves over LE Audio and stayed connected.
* **Real bad values:** on November 10, 2026, it caught Fast Pair's real bad values for the first time
  on both devices. On the Googlebook, Fast Pair stored both earbuds as unknown with the case at 97%,
  and the helper put back 100% left and 100% right, which the earbuds had reported 2 minutes earlier
  while staying connected.
* **LE Audio, in the broken state after a restart:** I re-applied the earbuds' "allowed" LE Audio
  setting by hand while they were in my ears and not connected. They connected over LE Audio
  2 seconds later.
* **LE Audio, in the normal state:** with the earbuds back on the auto-connect list and in the closed
  case, I did the same. The connection request timed out after 30 seconds, the earbuds stayed on the
  auto-connect list, and when I took them out they connected by themselves over LE Audio. So the step
  does no harm after a restart where nothing went wrong.
* **On the Tab P12,** which does not use LE Audio, the new helper correctly reported that LE Audio is
  not in use and changed nothing.
* **The log check,** with the earbuds in the closed case: it read their state as on the auto-connect
  list, and the fix then said "already on LE Audio's auto-connect list; nothing changed". With the
  earbuds in my ears, it read them as connected.
* **The confirmation:** in a test run that re-applies the setting regardless, the helper re-applied
  it with the earbuds in the case and confirmed 35 seconds later that they were back on the
  auto-connect list. When I took them out, they connected by themselves over LE Audio.
* **The log reader's limits:** a small read finished normally, a large read stopped at a 4 KB cap,
  and an endless read stopped at a 2 second deadline. No reader process was left behind, and
  Bluetooth kept responding.
* **The self-check and the warning:** on my Googlebook every check passed. I added a test line marked
  `attention` to the self-check file, and the app showed the "Buds Battery Fix needs attention"
  notification with that text. After I took the line out, the app's next check removed the
  notification.

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

It answers "Successfully paired". The separate adb server on port 5045 keeps this key apart from your
own adb key. Under **Paired devices**, the new entry is named after the computer that paired it, the
same name as your own adb key, so the name does not tell them apart. The **Device fingerprint** on an
entry's details page does. This prints the fingerprint of the app's key:

```bash
awk '{print $1}' $C/adbhome/.android/adbkey.pub | base64 -d | md5sum | cut -c1-32 | tr a-f A-F | sed 's/../&:/g; s/:$//'
```

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

After each Bluetooth start, the LE Audio step adds a line like one of these:

```
LE Audio after a Bluetooth start: Pixel Buds 2a (XX:XX): already on LE Audio's auto-connect list; nothing changed
LE Audio after a Bluetooth start: Pixel Buds 2a (XX:XX): already connected over LE Audio; nothing changed
LE Audio after a Bluetooth start: Pixel Buds 2a (XX:XX): re-applied their allowed LE Audio setting (the system log shows they were never put back on LE Audio's auto-connect list), so they are back on LE Audio's auto-connect list
LE Audio after a Bluetooth start: Pixel Buds 2a (XX:XX): re-applied their LE Audio setting; confirmed back on LE Audio's auto-connect list
LE Audio is not in use on this device; nothing to put back on its auto-connect list
```

At every start the log also says `self-check: all checks passed`, or how many items need attention,
and after an update it names what changed, for example
`an update was installed since the last start: Google Play services was ... and is now ...`.

### Commands

Run these with `adb shell sh /data/local/tmp/budsfix/budsfix.sh <command>`:

* `status`: whether it runs, the fixes this month, the last fix, every self-check item that needs
  attention with a summary line, and the end of the log;
* `stop`: stops the fix; it stays stopped, even after restarts, until `start`;
* `start`: starts it again after a stop.

## Updating

Build the new version and install it over the old one with `adb install -r --user <USER_ID>
BudsBatteryFix.apk`. The app notices its own update, copies a changed supervisor script to the device
and restarts the helper with the new code. I tested this on my Googlebook.

### Testing it yourself

The helper has three test runs. Each one runs by hand, exits by itself, and leaves the running fix
alone. Copy the app to the device first, then run them from `/data/local/tmp/budsfix`:

```bash
adb push BudsBatteryFix.apk /data/local/tmp/budsfix/helper-test.apk
adb shell 'cd /data/local/tmp/budsfix && timeout 40 app_process -cp helper-test.apk / local.budsfix.Helper test-log-reader'
adb shell 'cd /data/local/tmp/budsfix && timeout 30 app_process -cp helper-test.apk / local.budsfix.Helper test-le-state'
adb shell 'cd /data/local/tmp/budsfix && timeout 100 app_process -cp helper-test.apk / local.budsfix.Helper test-rearm-force'
```

* `test-log-reader` checks the log reader's three endings: finished, stopped at the size cap and
  stopped at the deadline.
* `test-le-state` changes nothing. It prints Bluetooth's process and what the log says about the
  earbuds' LE Audio state.
* `test-rearm-force` re-applies the earbuds' allowed LE Audio setting even when they are already on
  the list, then confirms the result and exits. While they are connected it changes nothing and exits
  at once. Run it with the earbuds in the closed case.

## Uninstalling

```bash
adb shell sh /data/local/tmp/budsfix/budsfix.sh stop
adb uninstall --user <USER_ID> local.budsfix
```

Then remove the app's key under **Paired devices** on the Wireless debugging screen: open each entry's
details, find the one whose **Device fingerprint** matches the command in step 4, and tap **Forget**.
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
* **The app says Needs attention.** The reason is shown in the app and in the notification, and
  `budsfix.sh status` lists it too. The most likely cause is a system update that changed something
  the fix relies on. Look in the log for the "an update was installed" line to see which update it
  was. Until it is solved, the parts of the fix that still work keep running.
* **The log says LE Audio is switched off for them.** LE Audio is switched off for the earbuds in
  their Bluetooth settings, so the helper leaves it that way. Switch it on there if you want it.
* **The earbuds still did not connect by themselves after a restart.** Look for the "LE Audio after a
  Bluetooth start" line in the log. If it is missing, the helper had not yet seen that Bluetooth start.

## Safety and privacy notes

* The helper holds no wake lock and uses no network. It only writes the four earbud values, left and
  right level and charging, of paired Fast Pair headsets, and only in the one bad combination described
  above.
* Its only other action is the LE Audio step: once after each Bluetooth start, and only when the
  earbuds are missing from LE Audio's auto-connect list or the log cannot tell, it sets the Pixel
  Buds' LE Audio setting to "allowed" when it already is "allowed", so the value never changes. It
  never touches Classic Bluetooth audio or any other device.
* It reads Bluetooth's log only for that step, once per Bluetooth start plus the confirmation, with a
  deadline and a size cap.
* Besides its monthly log, it rewrites only its own small state files in `/data/local/tmp/budsfix`:
  `health.txt`, `versions.txt` and `le-audio-rearm.txt`. The test runs described below use their own
  files whose names start with `test-`.
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
* The LE Audio step only handles earbuds whose name or model contains "Pixel Buds".
* At the time of publishing, the restart that loses the race has not happened again with the fix
  installed. I tested that state by hand, and the real restart I did with the fix installed went the
  normal way.
* When the step does re-apply the setting while the earbuds are in the case, LE Audio's connection
  request waits up to 30 seconds before it times out, so for that time the Bluetooth settings may show
  the earbuds as connecting. When they are already on the list, which is the normal case, nothing
  happens at all.
* The log check depends on the wording of Bluetooth's own log lines. If an update changes it, the
  check cannot tell, so the step re-applies the setting anyway and says that it could not confirm the
  result.

## Files

* `app/` is the Buds Battery Fix app:
  * `src/kotlin/local/budsfix/Helper.kt` is the helper that watches and corrects the stored values,
    does the LE Audio step and runs the self-check;
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
