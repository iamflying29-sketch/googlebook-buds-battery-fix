# Buds Battery Fix

Buds Battery Fix keeps the left and right battery levels of my Pixel Buds 2a showing on my Lenovo
Googlebook 15 and my Lenovo Tab P12. Google's Fast Pair sometimes stores both earbuds as "unknown"
while the charging case has a real level, so Google's Batteries widget and the Bluetooth settings
show only the case. Buds Battery Fix notices that moment within a fraction of a second and puts back
the latest real levels that the earbuds themselves reported. Every new report from the earbuds passes
straight through, so the levels stay current. It never makes up a number.

Google's Fast Pair pop-up keeps its own copy of the levels and does not read those stored values. It
shows only the case once the earbuds have not reported for 2 minutes. So while my earbuds are
connected and out of the case, Buds Battery Fix makes sure they report in time, and the pop-up keeps
showing left, right and case.

On my Googlebook, LE Audio sometimes sounded flanged, with the left and right earbuds fading in and
out, while the Pixel Buds app kept a Classic Bluetooth connection to the earbuds open in the
background. While audio plays over LE Audio, Buds Battery Fix now closes that idle Classic connection
with Android's regular stop of the app, and the app reconnects as usual when I open it.

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
* It saves its latest real reading in a file, so a restart of the helper itself, for example after an
  app update, does not make it forget the reading. A saved reading is only used within the same boot of
  the device.
* It writes a line to its log for each fix, and the app shows how many fixes it made this month and
  when the last one was. At each fix it also saves the text of Google's pop-up and Play services'
  battery log lines in its folder, so the cause can be studied later.
* While the earbuds are connected and out of the case, it keeps Google's Fast Pair pop-up showing
  their levels. When the earbuds' last report is 90 seconds old, it briefly opens the connection that
  Play services uses to talk to the earbuds. Play services opens its own connection again within
  seconds, and the earbuds then report their real levels. When the earbuds reported by themselves, it
  does nothing.
* While audio plays to the Pixel Buds over LE Audio and the Pixel Buds app holds a Classic Bluetooth
  connection to them in the background, it stops that app with Android's regular stop and closes the
  idle Classic link. It never does this while the app is on screen or updating firmware.
* On a device where I switch it on, it turns Wireless debugging back on when Android turned it off
  because Wi-Fi dropped for a moment.
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

### Google's pop-up kept showing only the case

After the stored values were fixed, the Batteries widget and the Bluetooth settings showed all three
levels, but Google's Fast Pair pop-up still showed only the case now and then. I tested it directly:
I held the stored values at real levels for 11 seconds, and the pop-up did not change. So the pop-up
does not read them.

I then looked inside Google Play services 26.43.30 to see where the pop-up gets its numbers. It has
only two sources: the earbuds' encrypted battery advertisement, and the battery message the earbuds
send over their direct Fast Pair connection to the device, which Google calls the Message Stream.
Play services keeps a known earbud level against an "unknown" advertisement only for 2 minutes after
the earbuds last reported. That limit is the Play services setting
`FastPairConfig__battery_all_the_time_battery_info_expire_millis`, which is 120000 milliseconds by
default, and only apps with Google's signature may change it.

My earbuds advertise "unknown" for both earbuds while audio plays, and I have seen them do it once
while idle too. So whenever they went 2 minutes without a real report, the pop-up dropped to "Case"
alone. The Fast Pair specification says that when a Message Stream connects, the earbuds send their
battery levels to every connected device, and I confirmed that on both of my devices.

### LE Audio sometimes sounded flanged on my Googlebook

While I watched videos over LE Audio, the sound sometimes flanged, and the left and right earbuds
faded in and out. It happened only on my Googlebook. My Tab P12 plays over Classic Bluetooth and never
did it. The Googlebook's own audio path was clean: its counters showed no lost audio packets and no
buffer underruns. The links to the earbuds, however, timed out several times, and the Pixel Buds app
kept reopening a Classic Bluetooth connection to the earbuds in the background while LE Audio played.
When I force-stopped the Pixel Buds app at 00:41 one night as a test, the earbuds closed their
Classic link to the Googlebook, and I heard no flanging in the next 35 minutes. The flanging comes and
goes, so one test cannot prove the cause. I have not heard it with the fix running.

In its code, the Pixel Buds app opens its own connection to the earbuds only over Classic Bluetooth.
It has a receiver that Android
wakes whenever the earbuds' Classic link opens or closes, or their LE Audio or headset connection
changes. When the Classic link closes, the app checks whether the earbuds are still connected by any
profile, counts LE Audio as connected, and starts reopening its Classic connection within about a
second. That is
why simply stopping the app does not help: the earbuds close the idle Classic link about 4 seconds
later, that event wakes the app, and it reconnects.

### Wireless debugging switched itself off on my Tab P12

Wireless debugging on my Tab P12 turned off by itself without a restart. Android's log showed
"Network disconnected. Disabling adbwifi." at the moment Wi-Fi dropped for a moment, which happened
when my router's 24 hour address lease ran out. Android turns Wireless debugging off when Wi-Fi
disconnects, and the helper's app needs it to stay on.

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

### Remembering the last reading across restarts

The helper writes each real reading to `last-real.txt`, with the time it arrived, whether the earbuds
stayed connected since, and the device's boot ID. When the helper starts again within the same boot,
it takes that reading back, so a fix that is needed right after an app update still has a real
reading to put back. A reading from an earlier boot is never used.

### Keeping Google's Fast Pair pop-up right

Every 4 seconds while the earbuds are connected to the device and out of the case, the helper looks at
how long ago the earbuds last reported. Play services writes "The device pass battery info to seeker"
to its log at every report, whoever caused it, so the helper counts every report, including those the
earbuds send by themselves and those the other device caused. When the last report is 90 seconds old,
it knocks once:

* **On my Googlebook,** Play services uses the LE form of the Message Stream, an L2CAP channel on the
  LE link. The helper learns that channel number from Bluetooth's log, opens the same channel for a
  moment and closes it. The earbuds allow one such stream per device, so Play services' own stream
  closes, Play services opens it again within seconds, and the earbuds report. This never touches the
  Classic link.
* **On my Tab P12,** Play services uses the Classic form, an RFCOMM channel. The file `knock-rfcomm`
  in the helper's folder makes the helper knock there instead. The earbuds refuse the helper's
  connection, but the attempt still closes Play services' stream, and the earbuds report when Play
  services reconnects.

20 seconds after each knock, the helper checks Play services' log for the earbuds' report. Only then
does it count the knock as done. Otherwise the app says "Needs attention" with the reason. If the
pop-up ever drops the earbuds although they reported less than 112 seconds earlier, the self-check
says so too, because that would mean Play services missed the report or an update shortened its 2
minute limit. When the pop-up does drop the earbuds, the helper knocks at once, and the stored values
fix corrects the widget and the settings at the same moment.

### Keeping the Classic link quiet while LE Audio plays

Android never delivers events like "link closed" to an app that carries its "stopped" mark. A
force-stop sets that mark, but it also cancels the app's scheduled jobs and alarms and removes its
notifications, which I did not want. The adb shell user may set only the mark, without any of the
rest. So every few seconds while audio plays to the Pixel Buds over LE Audio, the helper checks
whether the Classic link to them is up, whether the Pixel Buds app is only in the background, and
whether Bluetooth's log shows that app's Classic connection as open. If all of that is true, it:

1. sets only the stopped mark on the Pixel Buds app;
2. stops the app with Android's regular stop, which ends its processes but keeps its jobs, alarms and
   notifications;
3. closes the idle Classic link from the device's side with Android 17's `disconnectAcl`, for the
   Classic transport only, so LE Audio keeps playing;
4. clears the mark 150 milliseconds after the link reads closed.

The mark lasted 612 milliseconds in my test, and a watchdog clears it after 3 seconds at the most if
anything stalls. If the helper ever stops while the mark is set, it clears the mark at its next start.
It acts at most every 3 minutes. It never acts while the app is on screen or runs a foreground service,
such as a firmware update. The app stays off Classic until I open it, Bluetooth restarts, or the
earbuds connect or disconnect again, for example from the case. Then it reconnects as usual, and the
fix acts again during the next LE Audio playback.

On my Googlebook's Bluetooth service, `disconnectAcl` reports an error code even when it does close
the link, so the helper ignores the code and waits until the link actually reads closed.

### Keeping Wireless debugging on, on the Tab P12

Only with the file `keep-wireless-debugging` in the helper's folder, the helper checks every 30
seconds whether Wireless debugging is on. If it is off, it switches it back on only when Android's
log shows that Android itself turned it off because Wi-Fi dropped, and only once Wi-Fi is connected
again, at most 3 times an hour. If Wireless debugging was turned off for any other reason, for example
by hand, it stays off.

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
* **Restarts and the saved reading:** on my Googlebook, the helper took its saved reading back at
  each of its 13 restarts between November 11 and 12, 2026, all of them after app updates.
* **Google's pop-up on my Googlebook:** for 15 minutes of video on November 12, 2026, the pop-up
  showed left, right and case at every minute I checked, except while the earbuds were in the case.
  Play services got the earbuds' report 7.2 to 7.4 seconds after each knock, and the knocks never woke
  the Classic link. Once a report took 18 seconds, so I moved the knock from 95 to 90 seconds.
* **A miss while idle:** on November 12 at 01:44, with no audio playing, the pop-up showed only the
  case for less than half a minute, starting exactly 2 minutes after the earbuds' last report. Until then the guard only
  knocked during audio, because that was the only time I had seen the earbuds advertise "unknown". It
  now knocks whenever the earbuds are connected and out of the case.
* **Google's pop-up on my Tab P12:** the earbuds refused the helper's RFCOMM connection, Play services
  reconnected over RFCOMM 5 seconds later, and it got the earbuds' report 5.8 seconds after the knock.
* **Both devices at once:** for 8 minutes on November 12, with the earbuds connected to both devices,
  the pop-up showed left, right and case on both at every minute I checked. On the Tab P12 no audio was
  playing, and its guard knocked four times. Play services got the earbuds' report 5.6 to 15 seconds
  after each knock on the Tab P12, and 7.2 to 10.3 seconds after each knock on the Googlebook.
* **The Classic link during LE Audio:** on November 12 at 02:04, while a video played over LE Audio,
  the helper stopped the Pixel Buds app with the regular stop and the Googlebook closed only the
  Classic link. The link was down 440 milliseconds after the request, and the stopped mark lasted
  612 milliseconds in total. Android did not wake the app. It restarted 10 seconds later through
  Android's companion device service and stayed off Classic, and 20 seconds later the link was still
  closed. I heard no flanging during that video. In the first try a minute and a half earlier, the
  helper had
  trusted `disconnectAcl`'s error code, cleared the mark after 18 milliseconds, and the app woke up and
  reconnected, which is why it now waits for the link to read closed.
* **Wireless debugging on my Tab P12:** on November 11 at 21:56, Android turned Wireless debugging off
  after a Wi-Fi drop, and the helper switched it back on within the same minute.
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

The pop-up guard and the Classic link fix add lines like these:

```
pop-up guard: knocked (audio is playing, last report 96 s ago): opened LE channel 240, in 246 ms
pop-up guard: Play services got the buds' report 7.3 s after the knock
flanging fix: audio plays over LE Audio while the Pixel Buds app held the Classic link in the background (6 (IMPORTANT_FOREGROUND)); regular stop done; Classic link close requested (code 2147483647), closed after 459 ms; stopped mark set for 612 ms
flanging fix: 20 s later the Classic link is still closed
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

### Switch files

These empty files in `/data/local/tmp/budsfix` switch single parts on or off. Create one with
`adb shell touch /data/local/tmp/budsfix/<name>` and remove it with
`adb shell rm /data/local/tmp/budsfix/<name>`. The helper reads them at every check.

* `no-popup-guard` switches the pop-up guard off.
* `no-classic-quiet` switches the Classic link fix off.
* `knock-rfcomm` makes the pop-up guard knock over RFCOMM. Use it on a device whose Play services
  uses the Classic form of the Message Stream, like my Tab P12 with Android 15.
* `keep-wireless-debugging` switches the Wireless debugging keeper on.

## Updating

Build the new version and install it over the old one with `adb install -r --user <USER_ID>
BudsBatteryFix.apk`. The app notices its own update, copies a changed supervisor script to the device
and restarts the helper with the new code. I tested this on my Googlebook.

### Testing it yourself

The helper has six test runs. Each one runs by hand, exits by itself, and leaves the running fix
alone. Copy the app to the device first, then run them from `/data/local/tmp/budsfix`:

```bash
adb push BudsBatteryFix.apk /data/local/tmp/budsfix/helper-test.apk
adb shell 'cd /data/local/tmp/budsfix && timeout 40 app_process -cp helper-test.apk / local.budsfix.Helper test-log-reader'
adb shell 'cd /data/local/tmp/budsfix && timeout 30 app_process -cp helper-test.apk / local.budsfix.Helper test-le-state'
adb shell 'cd /data/local/tmp/budsfix && timeout 100 app_process -cp helper-test.apk / local.budsfix.Helper test-rearm-force'
adb shell 'cd /data/local/tmp/budsfix && timeout 15 app_process -cp helper-test.apk / local.budsfix.Helper test-audio'
adb shell 'cd /data/local/tmp/budsfix && timeout 45 app_process -cp helper-test.apk / local.budsfix.Helper test-knock'
adb shell 'cd /data/local/tmp/budsfix && timeout 20 app_process -cp helper-test.apk / local.budsfix.Helper test-mark'
```

* `test-log-reader` checks the log reader's three endings: finished, stopped at the size cap and
  stopped at the deadline.
* `test-le-state` changes nothing. It prints Bluetooth's process and what the log says about the
  earbuds' LE Audio state.
* `test-rearm-force` re-applies the earbuds' allowed LE Audio setting even when they are already on
  the list, then confirms the result and exits. While they are connected it changes nothing and exits
  at once. Run it with the earbuds in the closed case.
* `test-audio` changes nothing. It prints whether music plays, which devices are active for LE Audio
  and Classic audio, and how long ago Play services last got a report from the earbuds.
* `test-knock` knocks once, in the form the switch files choose, and prints whether Play services got
  the earbuds' report within 20 seconds. It writes its self-check results to `test-health.txt`.
* `test-mark` changes nothing. It prints whether the Pixel Buds app carries Android's stopped mark and
  whether the three Android calls the Classic link fix needs are present.

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
* **Google's pop-up still showed only the case.** Look for "pop-up guard" lines in the log around that
  time, and in the evidence file of that moment in `evidence/`, which holds the pop-up's text then,
  5 seconds later and 30 seconds later. If the guard was not knocking, check that `no-popup-guard`
  does not exist. If its knocks get no report, the self-check says so.
* **The Pixel Buds app's Classic connection keeps coming back.** That is expected after you open the
  app, after a Bluetooth restart, and after the earbuds connect again. The fix closes it again during
  the next LE Audio playback, at most every 3 minutes. If the log says "open again" after each try,
  the helper waits 10 minutes before the next one.
* **The earbuds still did not connect by themselves after a restart.** Look for the "LE Audio after a
  Bluetooth start" line in the log. If it is missing, the helper had not yet seen that Bluetooth start.

## Safety and privacy notes

* The helper holds no wake lock and uses no network. It only writes the four earbud values, left and
  right level and charging, of paired Fast Pair headsets, and only in the one bad combination described
  above.
* Its other actions are these, and nothing else:
  * the LE Audio step: once after each Bluetooth start, and only when the earbuds are missing from LE
    Audio's auto-connect list or the log cannot tell, it sets the Pixel Buds' LE Audio setting to
    "allowed" when it already is "allowed", so the value never changes;
  * the pop-up guard's knock: it opens Play services' Message Stream channel to the earbuds for about
    a quarter of a second and closes it, which makes Play services' own connection to the earbuds
    pause for usually 5 to 10 seconds before Play services restores it;
  * the Classic link fix: during LE Audio playback only, the stopped mark on the Pixel Buds app for
    612 milliseconds in my test and never more than 3 seconds, Android's regular stop of that app,
    and closing the earbuds' idle
    Classic link, never the LE link;
  * the Wireless debugging keeper, only where its switch file exists.
* It reads Bluetooth's log and Play services' log only for these steps, always with a deadline and a
  size cap, and it reads the active notifications only to find the text of Google's battery pop-up.
* Besides its monthly log, it rewrites only its own small state files in `/data/local/tmp/budsfix`:
  `health.txt`, `versions.txt`, `le-audio-rearm.txt`, `last-real.txt`, `stream-channel.txt` and
  `stopped-mark.txt`. The evidence records go to `evidence/`, one file per moment, at most one a
  minute, and recording stops when the folder reaches 30 MB. The test runs described below use their
  own files whose names start with `test-`.
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

* **The Fast Pair pop-up is Google Play services' own notification.** It does not read the corrected
  values. The pop-up guard keeps it right by making the earbuds report in time. It relies on Play
  services' 2 minute limit and on the wording of its log line about the earbuds' reports. If an update
  changes either one, the self-check says so.
* While the guard knocks, Play services' own connection to the earbuds pauses for usually 5 to 10
  seconds, so for that time the pop-up and the Pixel Buds settings may not update.
* When the earbuds are connected to two devices that both run the fix, both devices often knock within
  a few seconds of each other, because both see the same reports. That does no harm, but one knock
  would be enough.
* The Classic link fix only acts on the Pixel Buds app, package
  `com.google.android.apps.wearables.maestro.companion`, and only while audio plays over LE Audio. The
  `disconnectAcl` call exists in Android 17; on older Android versions the fix cannot close the link.
* I cannot prove with a single test that the Classic link causes the flanging, because the flanging
  comes and goes. I have not heard it with the fix running.
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
    keeps Google's pop-up right, keeps the Classic link quiet during LE Audio, does the LE Audio step,
    keeps Wireless debugging on where asked, records evidence and runs the self-check;
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
