# Label lab

Sample shipping labels for testing the scanner. Each one uses a real street address from the Cumberland County address
pack (`data/cumberland/addresses.sqlite`), a made-up recipient name and tracking number, a fictional return address, a
Code 128 tracking barcode (GS1 style, `420` + ZIP + 22-digit number) and a Data Matrix, with a little warp, shading,
smudges and scuffs. Generic layout, no carrier or retailer logos, and a "TEST LABEL" footer: these are not shipping labels.

```
cd tools/label-lab && npm install      # once: the barcode library (bwip-js)
python3 server.py                      # http://localhost:8765
```

Space = new address, R = re-roll the damage. Sliders (or URL settings of the same names, 0 to 2) control:

| Setting | What it imitates |
|---|---|
| `warp`, `dirt`, `soft` | a hand-held photo: tilt and ripple, smudges and scuffs, slight blur |
| `fade` | a worn thermal print head: lighter print overall, some lines and barcodes nearly gone, washed-out bands |
| `streak` | dead print-head pixels: thin white vertical lines (some full height, some part-way) and a few skipped rows |
| `feed` | uneven paper feed: print squeezed together in some bands and stretched in others |

`/?clean` shows an undamaged label; `?seed=N` makes a label repeatable. `test_scanner.py` and `camera_cycle.py` take the same settings.
