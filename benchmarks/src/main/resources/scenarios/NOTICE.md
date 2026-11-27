# Engine comparison scenarios

The scenarios follow the "Camunda 7 Alternatives Benchmark" by Marcin Pankowski
(https://github.com/Marcin-Pankowski/camunda7-alternatives-benchmark, Apache License 2.0), shown in the video
"BPMN Engine Benchmark: CIB seven, Operaton, Flowable, Kogito and Camunda 8 Speed Test"
(https://www.youtube.com/watch?v=-7zLxZNt198).

- `pricing.dmn` is that repository's generated `apps/operaton/src/main/resources/pricing.dmn` (100 rules, hit policy
  COLLECT with aggregation SUM), unchanged.
- `pricing-rules.json` is that repository's `load/scenario-b-rules.json`, the same rules as data; the benchmark uses
  it to compute the expected fee of every instance.
- `scenario-a.bpmn` and `scenario-b.bpmn` are the same processes as there, without diagram layout.
