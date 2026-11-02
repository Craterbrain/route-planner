# Label lab

Sample shipping labels for testing the scanner. Each one uses a real street address from the Cumberland County address
pack (`data/cumberland/addresses.sqlite`), a made-up recipient name and tracking number, a fictional return address, a
Code 128 tracking barcode (GS1 style, `420` + ZIP + 22-digit number) and a Data Matrix, with a little warp, shading,
smudges and scuffs. Generic layout, no carrier or retailer logos, and a "TEST LABEL" footer: these are not shipping labels.

```
cd tools/label-lab && npm install      # once: the barcode library (bwip-js)
python3 server.py                      # http://localhost:8765
```

Space = new address, R = re-roll the damage, sliders for warp / dirt / softness; `/?clean` shows an undamaged label.
