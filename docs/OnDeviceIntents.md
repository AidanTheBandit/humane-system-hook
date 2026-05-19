# On Device Intents

A rough listing of all built in Humane device intents. These are intended to be recognized on device, without being sent to the server/LLM.

| Action | Source | Trigger regex / rough query | Slots |
|---|---|---|---|
| GetCurrentTime | regex | what time is it | none |
| GetBatteryLevel | semantic | Rough: “battery level”, “what’s my battery”, “how much battery left” | none |
| AmIOnline | semantic | Rough: “am I online”, “do I have internet”, “am I connected” | none |
| GetBluetoothStatus | semantic | Rough: “bluetooth status”, “is bluetooth on” | none |
| GetAirplaneModeStatus | semantic | Rough: “airplane mode status”, “is airplane mode on” | none |
| GetCurrentVolume | regex | (what is\|tell me) the( current)? volume( level)?$ | none |
| SetVolume | regex | (increase\|raise\|lower\|set\|adjust)( the)? volume (level )?to (?<Text>...)$ | Text |
| IncrementVolume | regex | (increase\|raise)( the)? volume$ | none |
| DecrementVolume | regex | (decrease\|lower)( the)? volume$ | none |
| GetPhoneNumber | regex + semantic | what is my phone number; what is my number | none |
| GetCurrentLocation | regex | what is my location | none |
| GetSerialNumber | regex | what is my( pin's)? serial number$ | none |
| TurnOnWifi | regex + semantic | (enable\|turn on) wifi | none |
| TurnOffWifi | regex + semantic | (disable\|turn off) wifi | none |
| ConnectToWifi | regex | connect( to)? (.*)wi-fi( network)?$ | action has SSID, regex does not capture it |
| DisconnectWifi | regex | disconnect( my)? (.*)wi-fi( network)?$ | none |
| WifiQrScan | regex | (scan\|connect)( using)?( with)?( a)?( the)?( to)? wi-fi( using)?( with)?( a)? qr code$ | none |
| TurnOnBluetooth | regex + semantic | (enable\|turn on) bluetooth | none |
| TurnOffBluetooth | regex + semantic | (disable\|turn off) bluetooth | none |
| TurnOnAirplaneMode | regex + semantic | (enable\|turn on) airplane mode | none |
| TurnOffAirplaneMode | regex + semantic | (disable\|turn off) airplane mode | none |
| TurnOnCellularData | regex | (enable\|turn on) cellular data$ | none |
| TurnOffCellularData | regex | (disable\|turn off) cellular data$ | none |
| TurnOnCellularRoaming | regex | (enable\|turn on) cellular roaming$ | none |
| TurnOffCellularRoaming | regex | (disable\|turn off) cellular roaming$ | none |
| TurnOffDevice | regex + semantic | turn off device; power down | none |
| Reboot | regex + semantic | reboot$; restart$ | none |
| FactoryReset | regex + confirmation | factory reset$; then confirm with “yes/confirm/ok” or cancel with “no/cancel/abort” | none |
| TrustLock | regex | (enable\|turn on) trust lock$ | none |
| SetUpTouchcode | regex | set up touchcode( to)?( unlock)?$ | none |
| OpenDialpad | regex + semantic | (open\|show\|show me) (the dial pad\|dial pad) | none |
| OpenContacts | regex + semantic | (open\|show\|show me) (my contacts\|contacts) | none |
| OpenRecentCalls | regex + semantic | (open\|show\|show me) (my recent calls\|recent calls\|my call log\|the call log) | none |
| OpenDialerHome | regex + semantic | (open\|show\|show me) (the phone\|phone) | none |
| CallPerson | regex | (call\|dial\|make a phone call to\|make a call to\|place a call to\|place call to) (?<To>...); also emergency forms like please call 911 | To |
| OpenRecentPhotos | regex + semantic | (open\|show\|show me) (my photos\|my recent photos) | optional device-only field |
| CapturePhotograph | semantic | Rough: “take a photo”, “take a picture”, “capture a photograph” | none |
| CaptureVideo | semantic | Rough: “record a video”, “take a video”, “capture video” | optional device-only field |
| SetTimer | regex | timer for (?<Duration>a\|an\|[0-9]+) (?<Unit>seconds?\|minutes?\|hours?)$; (?<Duration>...) (?<Unit>...) timer$; (start\|set\|begin\|add\|create)( a)? timer$ | Duration, Unit |
| SetAlarm | regex | (start\|set\|make\|add\|create)( a\| an)? alarm (for\|at) (?<time>HH:MM)( (?<ampm>am\|pm))?$; also hour-only (?<HourTime>...) | time, HourTime, ampm |
| DisplayTimer | regex | (show me \|what are \|what is \|where is \|where are )(my \|the \|)timer(s)?$; (how much time\|time remaining\|time left)... | action has id, regex does not capture it |
| DisplayAlarm | regex | (show me \|what are \|what is \|where is \|where are )(my \|the \|)alarm(s)?$ | action has id, regex does not capture it |
| PauseTimer | regex | (pause\|stop)( the\| my)? timer$ | action has id, regex does not capture it |
| ResumeTimer | regex | (resume\|restart)( the\| my)? timer$ | action has id, regex does not capture it |
| EditTimer | regex | (extend\|increase\|lengthen)( the)? timer( (by\|to))? (?<Duration>a\|an\|[0-9]+) (?<Unit>seconds?\|minutes?\|hours?)$; (add\|extend\|increase) (?<Duration>...) (?<Unit>...)( to( the\| my)? timer)?$ | Duration, Unit |
| WorldClock | regex | current time in (?<Location>...); what time is it in (?<Location>...); what's the current time in (?<Location>...) | Location |
| ClearUnderstandingContext | semantic + seq2seq | Rough: “clear context”, “forget that”, “start over” | none |
| TriggerBugReport | regex | (file\|start\|create\|report)( a)? bug( report)?( about)? (?<Text>.+) | Text |
| SystemTraceStart | semantic | Rough: “start system trace”, “begin tracing”, “start trace” | none |
| SystemTraceStop | semantic | Rough: “stop system trace”, “end tracing”, “stop trace” | none |
Additional regex-local actions from the full table:

## Other Actions

| Action | Source | Trigger regex / rough query | Slots |
|---|---|---|---|
| CatchMeUp | regex + semantic | (can you \|please )?(catch me up); what did i miss; what's new; what's been happening | none |
| ComposeMessage | regex | Static: message 911; dynamic contacts: send message to <contact> saying <Message> | To, Message |
| Translate | regex + semantic | (set to\|translate in\|translate to\|set translate language to\|set language to) (?<Target><language>) | Target |
| PlayMusic | regex + seq2seq | play (?<Track>...); play album (?<Album>...); play artist (?<Artist>...); play genre (?<Genre>...); playlist forms | Track, Artist, Album, Genre, Playlist, Option |
| PlayFeaturedMusic | regex | play music; play something; play featured music/playlists | none |
| PlayCurrentTrackRadio | regex | play current song radio; play similar songs to current track | none |
| PlayFavoriteTracks | regex | play my favorite songs/tracks; play my tidal favorites | none |
| GetMusicQueue | regex | what song is up next; what track just played; what is previous in the queue | none |
| SaveCurrentTrackToFavorites | regex | (save\|favorite\|add) (the \|this )?(song\|track)( to ...)? | none |
| PauseMusic | semantic + seq2seq | Rough: “pause music” | none |
| ResumeMusic | semantic + seq2seq | Rough: “resume music” | none |
| NextTrack | semantic + seq2seq | Rough: “next track”, “skip song” | none |
| PreviousTrack | seq2seq | Rough: “previous track”, “go back” | none |
| TurnOnAmberAlert | regex | (enable\|turn on) amber alerts$ | none |
| TurnOffAmberAlert | regex | (disable\|turn off) amber alerts$ | none |
| TurnOnEmergencyAlert | regex | (enable\|turn on) emergency alerts$ | none |
| TurnOffEmergencyAlert | regex | (disable\|turn off) emergency alerts$ | none |
| TurnOnPublicSafetyAlert | regex | (enable\|turn on) public safety alerts$ | none |
| TurnOffPublicSafetyAlert | regex | (disable\|turn off) public safety alerts$ | none |

## Behind Feature Gates

| Action | Gate | Trigger |
|---|---|---|
| ChangeQuickAction | QUICK_ACTIONS_REMAPPING_FLAG | swap/change/set/make ... quick action ... to (?<action>notes\|translation\|messages...) |
| Tickle | THE_TICKLE | tickle my fancy; tickle tickle tickle; tickle |
| AddIfThenEntry | VISION_ACTIONS_ENABLED | if you see (?<If>.+) then (?<Then>.+) |
| ClearIfThenMap | VISION_ACTIONS_ENABLED | (clear\|erase\|delete)( the)? vision actions |
| GetIfThenMapSize | VISION_ACTIONS_ENABLED | (get\|tell)( me)?( the)? number of( the)? vision actions |
| IdentifyFood | humane_food_enabled | (whats\|what is) (this\|that\|it); what are these/those; what am i holding |
| FoodConsumptionQuery | humane_food_enabled | list food |
| StartActivityTracker | FITNESS_TRACKER_ENABLED | (start\|record\|track) ... (run\|bike ride\|walk\|hike\|workout...) |
| StopActivityTracker | FITNESS_TRACKER_ENABLED | (stop\|end\|finish) ... (run\|bike ride\|walk\|hike\|workout...) |