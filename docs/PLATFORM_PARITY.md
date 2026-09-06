# Android / iOS navigation parity

TesNav treats Android and iOS as two clients of one navigation contract. UI
layout may follow each platform, but the following user and NavAssist behavior
must remain equivalent:

- fuzzy destination search, current-address display, and selectable POI results;
- local recent search history (20 entries, newest first, deduplicated queries
  and selected places), repeat search/reselect, single deletion and clear-all;
- a missing-Key setup screen before AMap SDK objects are created, local Key
  persistence, a settings entry, and platform-specific official setup guidance;
- copyable iOS Bundle ID or Android package name and current signing SHA-1;
- up to three route alternatives with time, distance, toll, and traffic-light counts;
- real-time GPS navigation, simulation, stop, pause/resume simulation, internal
  voice guidance, and mute/resume voice;
- canonical v3 snapshot broadcast on unauthenticated UDP 4213, with a matching
  session/sequence acknowledgement before either App reports online;
- visible C3XL source address and connection status without a required token or
  pairing step;
- GCJ-02 route-relative location, route matching, route revisions, monotonic
  sequence numbers, stable maneuver event IDs, and 500 ms snapshot lifetime;
- the same maneuver vocabulary, including directional ramp, exit, merge, turn,
  U-turn, and roundabout events;
- navigation start forces both platforms to resume LAN broadcast immediately,
  so a changed C3XL address is learned from the next matching acknowledgement;
- the same lane-action vocabulary and the same invalid recommendation values
  (`15`, `22`, and `255`);
- GPS weakness is diagnostic and simulation is never control-active.

Platform integrations outside navigation are intentionally not parity
requirements. Android's Home Assistant / Tesla reverse-sync and legacy
WebSocket debugging remain Android-only until separately specified.
