# Android / iOS navigation parity

TesNav treats Android and iOS as two clients of one navigation contract. UI
layout may follow each platform, but the following user and NavAssist behavior
must remain equivalent:

- fuzzy destination search, current-address display, and selectable POI results;
- up to three route alternatives with time, distance, toll, and traffic-light counts;
- real-time GPS navigation, simulation, stop, pause/resume simulation, internal
  voice guidance, and mute/resume voice;
- authenticated v3 discovery on UDP 7765 and signed snapshots on TCP 7766;
- one pinned tici identity, visible connection status, and a forget-pairing action;
- GCJ-02 route-relative location, route matching, route revisions, monotonic
  sequence numbers, stable maneuver event IDs, and 500 ms snapshot lifetime;
- the same maneuver vocabulary, including directional ramp, exit, merge, turn,
  U-turn, and roundabout events;
- the same lane-action vocabulary and the same invalid recommendation values
  (`15`, `22`, and `255`);
- GPS weakness is diagnostic and simulation is never control-active.

Platform integrations outside navigation are intentionally not parity
requirements. Android's Home Assistant / Tesla reverse-sync and legacy
WebSocket debugging remain Android-only until separately specified.
