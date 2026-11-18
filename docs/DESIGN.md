# SimBridge design language

The main phone app follows Material 3 and the phone's own colours (Material You). This page is the
contract for new screens, so the app stays consistent.

## Places

Four places sit in the bottom navigation bar, in this order:

| Place | What it holds |
| --- | --- |
| **Messages** | Conversations: search, filters, swipe actions, multi-select. |
| **Calls** | Recent calls, voicemails, and the call features (voicemail and screening, translation, quiet hours, blocked numbers). The dial button opens the phone app. |
| **SIM phone** | The SIM phone at a glance (picture, battery, link), its switches as tiles, and its tools. |
| **Settings** | The link state, then one row per section: Connection, Calls, Messages, App and security. |

The app opens on **SIM phone**. Back from another place returns there.

Everything else is **one level down**: a page with a back arrow, opened from a list row. No page
has its own tab bar. Pages swipe sideways between places, except Messages, where a sideways swipe
acts on a conversation.

## Building blocks (`ui/Design.kt`)

- **PageScaffold**: a small top bar (title, back arrow, actions) and a scrolling column with 16 dp
  margins. No large collapsing headers.
- **Group / GroupRow**: rows of one topic in one rounded container, 2 dp apart, with big corners at
  the ends of the group and small corners between rows, as in Android's own Settings. A row has a
  tinted round icon, a title, an optional summary, and a chevron when it opens a page.
- **Tile**: a switch the way Quick Settings shows one. It is filled with the primary container when
  on, plain when off, and shows a spinner while the SIM phone works on it. A long press opens the
  page with its details.
- **StatusPill**: a dot and a word for a state (connected, offline).
- **Panel**: a rounded surface for free-form content (charts, maps, the dial pad).

## Colour

- Surfaces come from the colour scheme. Cards use `surfaceContainer`; the SIM phone card uses
  `surfaceContainerHigh`. Nothing uses elevation shadows.
- Colour means state, not decoration: `Status.ok` (green), `Status.warn` (amber) and `Status.bad`
  (error red) work in light and dark mode.
- Row icons use one tint per topic (`Tint`), at 14 % behind the icon. The tint is a hint only; the
  text always says the state.

## Spacing and type

- 16 dp page margins, 8 dp between items, 24 dp before a group label.
- Titles use `titleMedium`, summaries `bodyMedium` in `onSurfaceVariant`, group labels `labelLarge`
  in `primary`.

## Behaviour

- A switch on the SIM phone shows a spinner until the SIM phone confirms it. A failure shows a
  short message and the switch goes back.
- Changes that can cut the connection (mobile data, hotspot) ask first.
- With the fingerprint lock on, remote screen, USSD and switches ask for it (once per 2 minutes).
