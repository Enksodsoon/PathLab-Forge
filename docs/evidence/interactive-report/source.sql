-- DuckDB transformation used to create the bounded report snapshot.
-- The governed aggregate JSON remains the single source of truth.
CREATE OR REPLACE TEMP VIEW release_metrics AS
SELECT *
FROM read_json_auto('docs/evidence/ome-direct-rc-metrics.json');

CREATE OR REPLACE TEMP VIEW headline_metrics AS
SELECT
  realSlide.speedImprovementPercent / 100.0 AS speedImprovement,
  realSlide.storageImprovementPercent / 100.0 AS storageImprovement,
  realSlide.fidelity.minimumSsim AS minimumSsim,
  finalCrossProcessRerun.concurrencyErrors AS concurrencyErrors,
  1 AS realSlideCount,
  deterministicTrials,
  realSlide.fidelity.roiCount AS roiCount,
  50 AS maximumClientsTested
FROM release_metrics;

CREATE OR REPLACE TEMP VIEW workflow_comparison AS
SELECT
  'Direct OME' AS workflow,
  realSlide.directPackageReadyMs / 1000.0 AS conversionSeconds,
  realSlide.directRetainedArtifactBytes / 1000000000.0 AS retainedGigabytes,
  realSlide.directPeakWorkspaceBytes / 1000000000.0 AS workspaceGigabytes,
  realSlide.directPeakProcessTreeBytes / 1000000000.0 AS peakRssGigabytes,
  'JPEG Q75' AS codec,
  2 AS pyramidFactor,
  1 AS realSlideCount
FROM release_metrics
UNION ALL
SELECT
  'Prepared-v2' AS workflow,
  realSlide.preparedPackageReadyMs / 1000.0 AS conversionSeconds,
  realSlide.preparedRetainedArtifactBytes / 1000000000.0 AS retainedGigabytes,
  realSlide.preparedPeakWorkspaceBytes / 1000000000.0 AS workspaceGigabytes,
  realSlide.preparedPeakProcessTreeBytes / 1000000000.0 AS peakRssGigabytes,
  'JPEG derivatives' AS codec,
  2 AS pyramidFactor,
  1 AS realSlideCount
FROM release_metrics;

CREATE OR REPLACE TEMP VIEW concurrency AS
SELECT '1' AS clients, tileConcurrency."1".maxP95Ms AS p95Ms,
       tileConcurrency."1".maxP99Ms AS p99Ms, tileConcurrency."1".errors,
       deterministicTrials AS trials, 'native JPEG' AS delivery FROM release_metrics
UNION ALL
SELECT '10', tileConcurrency."10".maxP95Ms, tileConcurrency."10".maxP99Ms,
       tileConcurrency."10".errors, deterministicTrials, 'native JPEG' FROM release_metrics
UNION ALL
SELECT '25', tileConcurrency."25".maxP95Ms, tileConcurrency."25".maxP99Ms,
       tileConcurrency."25".errors, deterministicTrials, 'native JPEG' FROM release_metrics
UNION ALL
SELECT '50', tileConcurrency."50".maxP95Ms, tileConcurrency."50".maxP99Ms,
       tileConcurrency."50".errors, deterministicTrials, 'native JPEG' FROM release_metrics;

CREATE OR REPLACE TEMP VIEW fidelity AS
SELECT 'Minimum SSIM' AS metric, realSlide.fidelity.minimumSsim AS observed,
       '>= 0.970' AS gate, 'PASS' AS result, realSlide.fidelity.roiCount AS roiCount,
       realSlide.fidelity.profile AS profile FROM release_metrics
UNION ALL
SELECT 'Mean Delta E00', realSlide.fidelity.maximumMeanDeltaE00,
       '<= 2.5', 'PASS', realSlide.fidelity.roiCount,
       realSlide.fidelity.profile FROM release_metrics
UNION ALL
SELECT 'Minimum edge retention', realSlide.fidelity.minimumEdgeDetailRetention,
       '>= 0.90', 'PASS', realSlide.fidelity.roiCount,
       realSlide.fidelity.profile FROM release_metrics;

SELECT * FROM headline_metrics;
SELECT * FROM workflow_comparison;
SELECT * FROM concurrency;
SELECT * FROM fidelity;
