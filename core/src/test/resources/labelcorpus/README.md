Recognizer output saved by `tools/label-lab/bench.py --save-failures TAG`, replayed by `LabelCorpusTest`.
Files here must parse to their true address. `unreadable/` holds failures no parser rule can fix (a wrong digit or letter in the
street, no street line read, the city line missing); they are kept as a record and are not replayed.
