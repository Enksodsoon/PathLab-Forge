# PathLab ADAPT research and manuscript workflow

ADAPT’s research tooling is offline-first and evidence-bound. It does not search
the web, invent references, fit an inferential model, submit a paper, or decide
whether institutional review is required. Faculty investigators supply and sign
the records; the local institutional authority decides approval.

## Gate 0: formal novelty review

Start from `ADAPT_GATE0_TEMPLATE.json`. Create one exact, dated query and
screening record for every source below.
Record stable result identifiers, inclusion/exclusion decisions, reasons, the
reviewer, and source locations. A source that cannot be accessed—especially a
subscription database—must be recorded as `unavailable` with its reason. Do not
describe an unavailable source as searched.

| Source group | Registry database value |
|---|---|
| PubMed | `pubmed` |
| PubMed Central | `pmc` |
| Crossref | `crossref` |
| OpenAlex | `openalex` |
| arXiv | `arxiv` |
| IEEE Xplore | `ieee-xplore` |
| ACM Digital Library | `acm-digital-library` |
| Scopus | `scopus` |
| Web of Science | `web-of-science` |
| Applicable trial registries | `trial-registries` |
| WIPO PATENTSCOPE | `wipo-patentscope` |
| Google Patents | `google-patents` |
| Product documentation | `product-documentation` |

The seed template marks every source `unrun`; it cannot be frozen or used for a
novelty claim. The software does not claim these searches have been run. A formal review must
be performed and signed by investigators. Until then, the strongest permitted
novelty wording is “to our knowledge.” “First ever” is blocked.

## Evidence registry

Every manuscript claim is keyed to a strictly formatted DOI or PMID, exact source location or span,
study-design tag, allowed wording, investigator signoff, and verification date.
Unknown citation IDs and investigator-entered numbers are rejected. Generated
numbers come only from frozen analysis rows and are mapped in `tables.json`.

## Approved normal-use analysis

The prespecified design is nonrandomized implementation. The primary keyed
outcome is delayed correctness; the primary spatial outcome is success within
the faculty-defined coordinate tolerance. Retention-adjusted efficiency is
reported beside raw correctness, active minutes, and hints.

The protocol plans a mixed-effects logistic model with learner and task
intercepts, adjusted associations only, no primary-outcome imputation, attrition
reporting, and inverse-probability sensitivity analysis. The offline engine does
not fabricate a fit: without approved matched human follow-up and the frozen
analysis implementation, the output remains `inconclusive` or descriptive only.

## Safe-AI literacy sequence

The generated supplement fixes the sequence: independent answer, approved true
claim paired with an approved unrelated source, source check, then immediate
debrief. It never randomizes deception or generates medical content.

## One-command reproduction

After freezing a JSON config and all input artifacts:

```powershell
pathlab-adapt reproduce-study --config .\study.json --output-dir .\frozen-output
```

The output directory must not exist. The command validates bounded inputs first,
writes to a temporary sibling, and atomically renames only after every hash and
size gate passes. It writes the immutable snapshot,
machine-readable table and figure data, IMRaD draft, limitations, investigator
dossier, supplement, safe-AI sequence, and a hash-verifying reproduction
manifest. Re-running against identical inputs in a different empty directory
produces identical artifact hashes.

Safe-AI literacy output is `pending_faculty_evidence` with no sequence unless a
signed verified-true evidence record and a separately approved unrelated-source
record are both present. Placeholder approval-like identifiers are forbidden.

No command in this workflow performs paper submission, deployment, production
activation, or external data transfer.
