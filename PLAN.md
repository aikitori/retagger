# Plan

## Done (v0.1)

- [x] core: read/write tags for all jaudiotagger formats, artwork without javax.imageio (Android)
- [x] core: file name patterns (tags from file name, rename), batch editing, natural sort order
- [x] app: pick a folder via SAF (remembered), subfolders, multi-select
- [x] app: single-file editor with cover, tags from file name, rename
- [x] app: batch editing with shared values, track numbering, cover for all files
- [x] English/German UI, CI with signed releases

## Open / ideas

- [x] Tested on a phone: MP3 (incl. files without valid audio header), fragmented M4A
- [ ] Test on a phone: FLAC, OGG; large folder; SD card
- [ ] Screenshots for the README
- [x] Convert to MP3 (MediaCodec + LAME), single and batch, optionally deleting originals
- [x] MP3 converter tested on a phone
- [x] Convert to M4A (AAC) and Opus, read/write Opus tags
- [ ] Test M4A and Opus conversion on a phone
- [ ] More fields (lyrics, BPM, compilation)
- [ ] "Open with" from other apps, save cover as file
- [ ] Undo for batch edits
