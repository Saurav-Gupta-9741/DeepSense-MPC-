# Test fixtures

Short excerpts of the MotionSense dataset (Malekzadeh et al., "Mobile Sensor Data
Anonymization", IoTDI 2019, https://github.com/mmalekzadeh/motion-sense), phone in
the front trouser pocket, 50 Hz, converted to Android sensor units
(`t_ns, ax, ay, az [m/s²], gx, gy, gz [rad/s]`).

`fixtures.json` lists each excerpt's source file and an independent cadence
reference (2 × stride frequency from the gyroscope's principal rotation axis).

Regenerate: `python model/export_test_fixtures.py --motionsense <motion-sense/data> --out <this dir>`
