# PIVOT Research Protocol

## Research claim to test

PIVOT tests whether coordinate-grounded, annotation-free whole-slide retrieval
practice improves visual navigation skill more efficiently than non-adaptive
retrieval practice. The software does not claim diagnosis, expertise, or novelty
by itself. Any later paper must complete a systematic literature and patent search
before using a "first" or "novel" claim.

## Why public, unannotated slides are sufficient

The target is a JPEG region sampled from a slide and the answer is that region's
hidden source coordinate. Therefore neither training nor scoring requires a
diagnostic label, segmentation, or pathologist-authored annotation. A public WSI
can be imported through Forge, converted to its validated local DZI, and used as
soon as its dataset license and study protocol permit it. Dataset labels, when
available, may only be used for stratified analysis; they are not answer keys.

Use slide-level splits, and where metadata permits, source-site or patient-level
splits. Never put tiles from one slide into more than one analysis split. Record
scanner, stain, tissue source, magnification, source collection, and license as
study covariates. The initial study should include multiple public collections
chosen after license review rather than tuning the compiler to one collection.

## Experimental arms

1. **Random baseline:** tissue-filtered tasks in random order, no adaptation.
2. **Difficulty baseline:** the same deterministic task pool ordered from easy to
   hard, no learner adaptation.
3. **PIVOT:** the same pool with coordinate error-driven adaptive selection.
4. **Optional model ablation:** an open foundation model may rank visual ambiguity
   or difficulty. It must use the same coordinate answer and may not create or
   change the target.

Keep query images, slide assignments, task count, viewer controls, feedback, and
session duration matched across arms. Use randomized assignment or a
counterbalanced crossover design. Pre-register exclusion rules and the primary
analysis before collecting participant outcomes.

## Locked MVP parameters

| Parameter | MVP value | Reason |
| --- | ---: | --- |
| Candidate ceiling | 512 tiles per slide | Bounded CPU, memory, and package I/O |
| Task ceiling | 12 per manifest | Short, repeatable session |
| Tissue fraction minimum | 0.18 | Reject near-white background |
| Luma deviation minimum | 5 | Reject low-information regions |
| Edge strength minimum | 4 | Reject low-structure regions |
| Scale gaps | 2x, 4x, 8x | Test overview-to-detail navigation |
| Initial task | Nearest moderate difficulty | Avoid an extreme first task |
| Adaptation step | 0.18 difficulty units | Visible but bounded response |
| Match threshold | <= 0.50 target widths | Submitted center is inside/near target |
| Close threshold | <= 1.25 target widths | Near miss |
| Confidence | 1-4 | Calibration without a neutral midpoint |

Changing a locked value requires a new algorithm version and a new manifest;
otherwise study arms would not be reproducible.

## Outcomes

Primary outcomes:

- normalized coordinate error in target widths;
- task completion time;
- within-session and between-session learning slope;
- success rate at the locked match threshold.

Secondary outcomes:

- pan distance normalized by source-slide diagonal;
- zoom reversals;
- hints and skips;
- completion and attrition;
- confidence calibration and overconfidence rate;
- performance by scale gap, difficulty band, slide, stain, scanner, and source
  collection.

Report medians and uncertainty intervals as well as means because time and error
are likely skewed. Analyze participants and slides as crossed effects. Determine
sample size from a pilot variance estimate and a pre-registered minimum effect;
do not invent a power claim from the software tests.

## Human role

No human is needed to author, annotate, approve, or score an individual task.
The learner is the human in the loop: they navigate, select, declare confidence,
and optionally request a hint. Investigators still choose eligible datasets,
review licenses and ethics, enroll participants, pre-register comparisons, and
interpret results. A pathologist is required only if a later study makes a
clinical or diagnostic claim.

## Reproducibility and safety

Each manifest records the source fingerprint, image-series identity, conversion
revision, algorithm version, seed, candidate counts, rejection counts, and task
coordinates. Each attempt records only the task identifier, submitted coordinate,
normalized error, elapsed time, navigation telemetry, confidence, and hint/skip
state. Research data stays local in the MVP and contains no diagnosis. Before
exporting study data, add explicit consent, pseudonymization, provenance, and
institutional review controls appropriate to the study.

Negative controls must include blank-heavy slides and synthetic slides that fail
the information threshold. A failure to compile is a valid quality-control result,
not a reason to lower thresholds silently.
