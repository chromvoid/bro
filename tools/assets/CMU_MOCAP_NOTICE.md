# CMU Graphics Lab Motion Capture Database

Retrieved 2026-10-03 from <https://mocap.cs.cmu.edu/>.
Used only by the isolated InBody character preview, not the production app.

The data used in this project was obtained from mocap.cs.cmu.edu.
The database was created with funding from NSF EIA-0196217.

## Upstream usage notice

> This data is free for use in research projects.
> You may include this data in commercially-sold products,
> but you may not resell this data directly, even in converted form.

The upstream page also requests the acknowledgement above when publishing
results. These motion data are not relicensed as CC0: the character's CC0
geometry and CMU motion capture have separate provenance.

## Selected capture

- Subject 79, motion 94, `flexing`.
- Catalog: <https://mocap.cs.cmu.edu/search.php?subjectnumber=79>.
- Skeleton: <https://mocap.cs.cmu.edu/subjects/79/79.asf>.
- Animation: <https://mocap.cs.cmu.edu/subjects/79/79_94.amc>.
- Capture metadata: <https://mocap.cs.cmu.edu/subjects/79/79_94.c3d>.
- Video reference: <https://mocap.cs.cmu.edu/subjects/79/79_94.mpg>.

The C3D header confirms 60 Hz and frames 9–943 inclusive; AMC contains 935
frames. Playback spans 934/60 seconds between the first and last samples.
The full source capture and generated files remain under ignored
`runtime/inbody-character/cmu/` and `runtime/inbody-character/assets/`.
Source file hashes and retargeting details are recorded in the generated
`cmu-flexing.json` manifest.
