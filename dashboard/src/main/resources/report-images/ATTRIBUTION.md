# Report image assets

These are frozen copies of freely-licensed photographs from Wikimedia Commons,
downloaded once and committed so the deterministic PDF reports and the
paperwork pages never depend on the network at render time. Identical assets
are what keep identical records rendering to identical bytes.

Do not re-compress or replace a file casually: every replacement changes the
bytes of every report that embeds it.

| File | Source page (Wikimedia Commons) | License |
| --- | --- | --- |
| `electronics.jpg` | https://commons.wikimedia.org/wiki/File:Caviar_2340_Hard_Drive_Circuit_Board_Side_1.jpg | CC0 |
| `fabric.jpg` | https://commons.wikimedia.org/wiki/File:Rolls_of_fabric_of_multiple_colors_(181099191).jpg | CC BY 3.0 |
| `hardware.jpg` | https://commons.wikimedia.org/wiki/File:DIN_914-like_1-4%22-20_UNC_x_9.5mm_hex_socket_screws.jpg | CC BY-SA 4.0 |
| `food.jpg` | https://commons.wikimedia.org/wiki/File:Nigerian_local_food_seller_and_buyer_market_scene.jpg | CC BY-SA 4.0 |
| `warehouse.jpg` | https://commons.wikimedia.org/wiki/File:An_Overview_of_Moving_Companies_and_Their_Use_of_Moving_Boxes.jpg | CC BY 2.0 |

All were fetched via the Commons API thumbnail endpoint and normalised to
≤800 px baseline JPEG with `sips`. They are illustrative reference pictures
for the goods family, not photographs of the actual consignment — the actual
dock evidence frames, when present, are embedded beside them and labelled as
such.
