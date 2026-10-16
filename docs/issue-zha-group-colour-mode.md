# Issue — a ZHA group reports `xy` right after a colour temperature command

Upstream, in zigpy/zha. Not something this project works around: the slider shows what HA
reports, and here HA reports a state the bulbs were never in.

## What we see

`light.nabu_casa_skyconnect_v1_0_lys_bibliotek` ("Lysgruppe bibliotek") is a ZHA group of two
Hue bulbs (`light.light_hue_06a306_bibliotek_light`, `light.hue_light_d21fb3_bibliotek_light`),
`supported_color_modes: [color_temp, xy]`. Setting a temperature on the slider after a colour
(2026-10-16, from a `state_changed` subscription):

```
17:32:41.001  xy          null   hs [135.5, 100]   the colour set before
17:32:45.817  color_temp  4000                     the group's own state after the command
17:32:45.876  xy          null                     59 ms later: back to xy, no temperature
17:32:46.727  color_temp  4000                     the members' real state
```

HA reports `color_temp_kelvin: null` for a light whose `color_mode` is not `color_temp`, so the
slider's temperature marker disappears for about a second and comes back.

## Cause

With ZHA's "Assume state of group" option on (`group_members_assume_state`), a group command
writes the commanded values into every member before any bulb answers
(`LightGroup._make_members_assume_group_state`,
`zha/application/platforms/light/__init__.py`). Its two colour branches each end in an `else`
that clears the assumed mode, so the second clears what the first set:

```python
if color_temp is not None:
    assumed_color_mode = self._color_mode
    assumed_color_temp = self._color_temp
else:
    assumed_color_mode = None
    assumed_color_temp = None

if xy_color is not None:
    assumed_color_mode = self._color_mode
    assumed_xy_color = self._xy_color
else:
    assumed_color_mode = None      # a temperature-only call loses its mode here
    assumed_xy_color = None
```

For a `color_temp`-only call each member gets `_color_temp` but keeps its old `_color_mode`
(`xy`). The group then recomputes from its members — "Report the most common color mode" — and
the most common is `xy`. Once the bulbs are polled (`_debounced_member_refresh`) their real
`color_temp` mode arrives and the group follows.

An `xy`-only call is unaffected, since the branch that sets its mode runs last: the bug is
specific to temperature.

## What the option changes

From the same file and `zha/application/platforms/__init__.py`:

| | assume on (default) | assume off |
|---|---|---|
| on a group command | members are written with the commanded values at once | members are untouched until they are polled or report |
| group recompute after a member changes | 0.05 s debounce (`ASSUME_UPDATE_GROUP_FROM_CHILD_DELAY`) | 0.5 s (`DEFAULT_UPDATE_GROUP_FROM_CHILD_DELAY`), "to ensure that we get updates from all members before updating the group entity" |
| after the command | the debounced member refresh polls the bulbs (cooldown 3 s), or at the end of a transition | the same |

Off, the buggy write never happens, so this flap should go. The cost is that the member lights'
own entities lag until polled, and a group can still show a mixed moment if its bulbs answer
more than 0.5 s apart. ZHA's docs: "Disable this if you have unreliable bulbs in a group that
sometimes don't respond, so the UI reflects the actual state."

Untested here: the option has not been turned off on this installation yet.

## Fix upstream

Clear the assumed mode only when neither colour was set:

```python
assumed_color_mode = None
assumed_color_temp = None
assumed_xy_color = None
if color_temp is not None:
    assumed_color_mode = self._color_mode
    assumed_color_temp = self._color_temp
if xy_color is not None:
    assumed_color_mode = self._color_mode
    assumed_xy_color = self._xy_color
```

No existing report found (searched zigpy/zha and home-assistant/core issues and PRs,
2026-10-16). Not filed yet: the maintainer's call.

## Not this issue

ZHA's "Prevent slider jumping during transitions" (`enable_light_transitioning_flag`) holds back
level and on/off reports during a transition only; colour attributes are not buffered. Whether a
temperature change also reports intermediate values during a fade is unconfirmed: a run of five
values a second apart (17:31:24–17:31:28) was either that or several presses. Logging the two
member bulbs alongside the group would tell.
