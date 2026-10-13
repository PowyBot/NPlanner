## New

- **Plans live on the server** – install the planner on the server too (it's needed on both sides now). Every plan in the world is shared with everyone on it: your edits show up for the others a moment later and theirs for you. If two people change the same plan at once, nothing is lost: the other version is kept as a "conflict copy".
- **Live machines** – machines you placed from a plan show how they're really doing: a green, yellow or red badge on the card and over the machine in the world (running and how far along, idle, switched off, needs maintenance, not built, wrong machine, structure incomplete). Look at one to see everything: progress, power, what it's making and why it stopped.
- **Fluid Tank** – a new card under Non-recipe → Other. Wire a cell or a fluid to it and it empties or fills cells for free, no Canner needed.
- **Split your leftovers** – when your machines make more than the plan uses (or not enough), the overview shows where the extra goes. Hit Split to share it out, or Auto to let the planner pick.
- **Surplus and Imported rows** – a card now shows what leaves or comes into the plan at a wired port with no drawer. Drag that row off to turn it into a drawer.
- **Update heads-up** – the planner tells you once when a new version is out.
- **Bigger tour** – the "?" tour now goes all the way from NEI to placing machines in the world. Quit it whenever and you keep the plan you built as "Tour plan".

## Better

- Dragging a wire lights up every card and port that can take it. Wire arrows are fewer, bigger and lighter, on the board and in the world.
- The plan over the world is calmer: a machine's card only pops up above it when you look at it (Shift + U), and each machine gets a small label with its name, tier, power and count (Shift + I). Wires say what they carry, always or only for the machine you look at. The gear explains every option, with its key. Machines can show as see-through, solid or not at all, and the keys list in the corner can be hidden (all in the gear).
- The settings show a live picture of what the world and minimap options do.
- Every world key (turn, pick up, remove, Focus, Labels) can be changed in Controls, and the minimap can be made see-through. It also shows whole cards one zoom step sooner.
- Rates you set on a card's port show up in the overview, and "~ Set" puts a rate on anything nothing sets yet.
- "(set count)" only nags on the part of the plan that actually needs a number.
- Steam machines lose the voltage chip (they don't use EU) and show how much steam they burn instead.
- Custom Rate moved into the new Other column in Non-recipe.
- Zooming out switches cards to the simple view a step later, so the text stays readable longer.
- The tour doesn't touch your GUI scale anymore.
- Plans solve several times faster on a computer with a few cores to spare, and the same plan always comes out with the same numbers.
- The planner buys raw stuff and runs your own machines instead of buying parts they could make, and only sells spare once nothing further down the line can use it. Machines nothing you pinned needs sit at 0 and ask for a count.
- No more little freeze after every change (dropping or deleting a drawer, anything): saving happens in the background now. Scrolling a count or rate updates the plan as you scroll instead of after you stop.
- Huge plans stay smooth: thousands of cards pan and zoom without slowing down, open much faster, and the plan over the world keeps up with thousands of placed machines. Wires no longer flicker when zoomed far out.
- Resource packs can restyle the plan button on NEI's recipe pages, like NEI's own buttons.

## Fixed

- Pasted plans (links and codes) get the right machines: no more Basic Extractors turning into Steam Extractors, or HV machines showing up as LV ones.
- Crop farms in pasted plans come in now, and crop cards share to the library properly.
- Generators in pasted Build plans keep their count instead of getting resized.
- Cards in pasted plans don't land on top of each other.
- Placing machines in the world: you can stack the next one on the one you just placed, and you can see which way R turns it. Each machine of a group says which it is (1/4, 2/4...).
- Placing a generator in the world shows its name, not a code.
- The rate unit (/min and friends), EU/t or Amps, and Peak or Avg stick when you close and reopen the planner.
