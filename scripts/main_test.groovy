#!/usr/bin/env groovy

// Run with:
// gradle --quiet --init-script scripts/main.groovy --init-script scripts/main_test.groovy help

def contexts = gradle.ext.PainlessContexts

assert [1, 2, 3].length == 3
assert [1, 2, 3].sum() == 6d
assert [1, 2, 3].sum { it * 2 } == 12d
assert [a: [b: [7]]].getByPath('a.b.0') == 7
assert 'a--b--'.splitOnToken('--').toList() == ['a', 'b', '']
assert 'painless'.encodeBase64().decodeBase64() == 'painless'
assert 'abc'.sha256() == 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'

CharSequence builder = new StringBuilder('abc')
GString interpolated = "${'abc'}"
assert builder.sha256() == 'abc'.sha256()
assert interpolated.encodeBase64().decodeBase64() == 'abc'
assert new ArrayList([1, 2, 3]).sum() == 6d
assert ([1, 2, 3] as Object[]).findAll { it > 1 } == [2, 3]
assert ([1, 2, 3] as int[]).sum() == 6d
assert ([1L, 2L] as long[]).collect { it * 2 } == [2L, 4L]
assert ([1d, 2d] as double[]).groupBy { it.intValue() } == [1: [1d], 2: [2d]]
assert ([true, false] as boolean[]).count { it } == 1
assert (['a' as char, 'b' as char] as char[]).join('-') == 'a-b'

List arrays = [
    ['x'] as Object[],
    [true] as boolean[],
    [1] as byte[],
    [2] as short[],
    ['c' as char] as char[],
    [3] as int[],
    [4L] as long[],
    [5f] as float[],
    [6d] as double[]
]
assert arrays.collect { it.asList().size() } == [1] * 9

// Adapted from Elasticsearch FactoryTests, EmitTests,
// ScriptedMetricAggContextsTests, SimilarityScriptTests, and PainlessExecuteApiTests.
assert contexts.names().size() == 21
assert contexts.execute('field', [params: [factor: 2], doc: [rank: [4]]]) {
    doc['rank'].value * params.factor
}.value == 8
assert contexts.execute('runtime', [doc: [rank: 4]]) {
    emit(doc['rank'].value)
    emit(doc['rank'].value + 1)
}.emitted == [4, 5]
assert contexts.execute('filter', [params: [max: 5], doc: [rank: 4]]) {
    doc['rank'].value < params.max
}.value
assert contexts.execute('score', [params: [max_rank: 8], doc: [rank: 4], _score: 2d]) {
    _score * doc['rank'].value / params.max_rank
}.value == 1d
assert contexts.execute('sort', [doc: [price: 20, quantity: 4]]) {
    doc['price'].value / doc['quantity'].value
}.value == 5

Map updateCtx = [_source: [count: 1], op: 'index']
contexts.execute('update', [params: [increment: 2], ctx: updateCtx]) {
    ctx._source.count += params.increment
}
assert updateCtx._source.count == 3

Map state = [:]
contexts.execute('metric_agg_init', [params: [initialVal: 1], state: state]) {
    state.testField = params.initialVal
}
contexts.execute('metric_agg_map', [state: state, doc: [amount: 3], _score: 2d]) {
    state.testField += doc['amount'].value * _score
}
assert contexts.execute('metric_agg_combine', [state: state]) {
    state.testField
}.value == 7
assert contexts.execute('metric_agg_reduce', [states: [[value: 2], [value: 5]]]) {
    states.collect { it.value }.sum()
}.value == 7d

Map similarityGlobals = [
    weight: 2d,
    query: [boost: 3f],
    field: [docCount: 10L, sumDocFreq: 20L, sumTotalTermFreq: 30L],
    term: [docFreq: 2L, totalTermFreq: 4L],
    doc: [freq: 4L, length: 8L]
]
assert contexts.execute('similarity', similarityGlobals) {
    weight * query.boost * doc.freq.value / doc.length.value
}.value == 3d
assert contexts.execute('analysis_predicate', [token: [term: 'ZO0240302403', position: 0]]) {
    token.term.length() == 12 && token.term.startsWith('ZO')
}.value
assert contexts.execute('watcher_condition', [ctx: [payload: [hits: [total: 2]]]]) {
    ctx.payload.hits.total > 0
}.value

assert contexts.execute('field') { 'painless'.substring(4) }.value == 'less'
assert 'outside'.intern().is('outside')

boolean deniedMethod = false
try {
    contexts.execute('field') { 'inside'.intern() }
} catch (MissingMethodException expected) {
    deniedMethod = expected.method == 'intern'
}
assert deniedMethod

boolean deniedProperty = false
try {
    contexts.execute('field') { 'x'.properties }
} catch (MissingPropertyException expected) {
    deniedProperty = expected.property == 'properties'
}
assert deniedProperty

println 'All Painless compatibility tests passed'
