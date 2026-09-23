# Wallet overflow icon theming fix

This version keeps popup text and popup background unchanged.

Menu icons are tinted by menu placement rather than by item name or a fixed count:
- action items rendered outside the overflow popup use the Toolbar theme's `textColorPrimary`;
- non-action items rendered inside the overflow popup use the Toolbar's actual `popupTheme` `textColorPrimary`.

The code uses `MenuBuilder.getActionItems()` and `getNonActionItems()`, so adding or removing menu items does not require maintaining an ID list or changing icon counts.

No hard-coded black/white icon colors are used for this behavior.
