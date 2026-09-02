#!/usr/bin/env groovy

import groovy.json.JsonSlurper
import groovy.lang.DelegatingMetaClass
import org.codehaus.groovy.runtime.GStringImpl

import java.lang.reflect.Array
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Installs the receiver-style APIs defined by Painless Augmentation. */
class PainlessAugmentation {
    private static final List<Class<? extends CharSequence>> STRING_TARGETS = [
        CharSequence,
        String,
        GString,
        GStringImpl
    ].asImmutable()
    private static final List<Class> ARRAY_TARGETS = [
        Object[].class,
        boolean[].class,
        byte[].class,
        short[].class,
        char[].class,
        int[].class,
        long[].class,
        float[].class,
        double[].class
    ].asImmutable()
    private static boolean installed

    static synchronized void install() {
        if (installed) {
            return
        }

        installIterable()
        installCollection()
        installMap()
        installString()
        installArrays()
        installMiscellaneous()
        installed = true
    }

    private static void installIterable() {
        List.metaClass.getLength = { -> delegate.size() }
        ArrayList.metaClass.getLength = { -> delegate.size() }

        Iterable.metaClass.any = { Closure predicate ->
            for (def value : delegate) {
                if (predicate.call(value) as boolean) {
                    return true
                }
            }
            false
        }
        Iterable.metaClass.asCollection = { ->
            delegate instanceof Collection ? delegate : copyIterable(delegate)
        }
        Iterable.metaClass.asList = { ->
            delegate instanceof List ? delegate : copyIterable(delegate)
        }
        Iterable.metaClass.count = { Closure predicate ->
            int count = 0
            for (def value : delegate) {
                if (predicate.call(value) as boolean) {
                    count++
                }
            }
            count
        }
        Iterable.metaClass.eachWithIndex = { Closure consumer ->
            int index = 0
            for (def value : delegate) {
                consumer.call(value, index++)
            }
            delegate
        }
        Iterable.metaClass.every = { Closure predicate ->
            for (def value : delegate) {
                if (!(predicate.call(value) as boolean)) {
                    return false
                }
            }
            true
        }
        Iterable.metaClass.findResults = { Closure filter ->
            def results = []
            for (def value : delegate) {
                def result = filter.call(value)
                if (result != null) {
                    results.add(result)
                }
            }
            results
        }
        Iterable.metaClass.groupBy = { Closure mapper ->
            Map results = new LinkedHashMap()
            for (def value : delegate) {
                def key = mapper.call(value)
                if (!results.containsKey(key)) {
                    results.put(key, [])
                }
                results.get(key).add(value)
            }
            results
        }
        Iterable.metaClass.join = { String separator ->
            StringBuilder result = new StringBuilder()
            boolean first = true
            for (def value : delegate) {
                if (first) {
                    first = false
                } else {
                    result.append(separator)
                }
                result.append(value)
            }
            result.toString()
        }
        Iterable.metaClass.sum = { ->
            double result = 0d
            for (Number value : delegate) {
                result += value.doubleValue()
            }
            result
        }
        Iterable.metaClass.sum = { Closure mapper ->
            double result = 0d
            for (def value : delegate) {
                result += (mapper.call(value) as Number).doubleValue()
            }
            result
        }
    }

    private static void installCollection() {
        Collection.metaClass.collect = { Closure mapper ->
            def results = []
            for (def value : delegate) {
                results.add(mapper.call(value))
            }
            results
        }
        Collection.metaClass.collect = { Collection destination, Closure mapper ->
            for (def value : delegate) {
                destination.add(mapper.call(value))
            }
            destination
        }
        Collection.metaClass.find = { Closure predicate ->
            for (def value : delegate) {
                if (predicate.call(value) as boolean) {
                    return value
                }
            }
            null
        }
        Collection.metaClass.findAll = { Closure predicate ->
            def results = []
            for (def value : delegate) {
                if (predicate.call(value) as boolean) {
                    results.add(value)
                }
            }
            results
        }
        Collection.metaClass.findResult = { Closure mapper ->
            findCollectionResult(delegate, null, mapper)
        }
        Collection.metaClass.findResult = { Object defaultResult, Closure mapper ->
            findCollectionResult(delegate, defaultResult, mapper)
        }
        Collection.metaClass.split = { Closure predicate ->
            List matched = []
            List unmatched = []
            for (def value : delegate) {
                (predicate.call(value) as boolean ? matched : unmatched).add(value)
            }
            [matched, unmatched]
        }
    }

    private static void installMap() {
        Map.metaClass.collect = { Closure mapper ->
            def results = []
            for (Map.Entry entry : delegate.entrySet()) {
                results.add(callMapClosure(mapper, entry))
            }
            results
        }
        Map.metaClass.collect = { Collection destination, Closure mapper ->
            for (Map.Entry entry : delegate.entrySet()) {
                destination.add(callMapClosure(mapper, entry))
            }
            destination
        }
        Map.metaClass.count = { Closure predicate ->
            int count = 0
            for (Map.Entry entry : delegate.entrySet()) {
                if (callMapClosure(predicate, entry) as boolean) {
                    count++
                }
            }
            count
        }
        Map.metaClass.every = { Closure predicate ->
            for (Map.Entry entry : delegate.entrySet()) {
                if (!(callMapClosure(predicate, entry) as boolean)) {
                    return false
                }
            }
            true
        }
        Map.metaClass.find = { Closure predicate ->
            for (Map.Entry entry : delegate.entrySet()) {
                if (callMapClosure(predicate, entry) as boolean) {
                    return entry
                }
            }
            null
        }
        Map.metaClass.findAll = { Closure predicate ->
            Map results = newSimilarMap(delegate)
            for (Map.Entry entry : delegate.entrySet()) {
                if (callMapClosure(predicate, entry) as boolean) {
                    results.put(entry.key, entry.value)
                }
            }
            results
        }
        Map.metaClass.findResult = { Closure mapper ->
            findMapResult(delegate, null, mapper)
        }
        Map.metaClass.findResult = { Object defaultResult, Closure mapper ->
            findMapResult(delegate, defaultResult, mapper)
        }
        Map.metaClass.findResults = { Closure mapper ->
            def results = []
            for (Map.Entry entry : delegate.entrySet()) {
                def result = callMapClosure(mapper, entry)
                if (result != null) {
                    results.add(result)
                }
            }
            results
        }
        Map.metaClass.groupBy = { Closure mapper ->
            Map results = new LinkedHashMap()
            for (Map.Entry entry : delegate.entrySet()) {
                def key = callMapClosure(mapper, entry)
                if (!results.containsKey(key)) {
                    results.put(key, newSimilarMap(delegate))
                }
                results.get(key).put(entry.key, entry.value)
            }
            results
        }
        Map.metaClass.getByPath = { String path -> getByPath(delegate, path, false, null) }
        Map.metaClass.getByPath = { String path, Object defaultValue ->
            getByPath(delegate, path, true, defaultValue)
        }
        List.metaClass.getByPath = { String path -> getByPath(delegate, path, false, null) }
        List.metaClass.getByPath = { String path, Object defaultValue ->
            getByPath(delegate, path, true, defaultValue)
        }
    }

    private static void installString() {
        STRING_TARGETS.each { Class<? extends CharSequence> target ->
            def metaClass = target.metaClass
            metaClass.encodeBase64 = { ->
                Base64.encoder.encodeToString(delegate.toString().getBytes(StandardCharsets.UTF_8))
            }
            metaClass.decodeBase64 = { ->
                new String(
                    Base64.decoder.decode(delegate.toString().getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8
                )
            }
            metaClass.splitOnToken = { String token -> splitOnToken(delegate.toString(), token, -1) }
            metaClass.splitOnToken = { String token, int limit -> splitOnToken(delegate.toString(), token, limit) }
            metaClass.sha1 = { -> digest(delegate.toString(), 'SHA-1') }
            metaClass.sha256 = { -> digest(delegate.toString(), 'SHA-256') }
            metaClass.sha512 = { -> digest(delegate.toString(), 'SHA-512') }
        }
    }

    private static void installArrays() {
        ARRAY_TARGETS.each { Class target ->
            def metaClass = target.metaClass
            metaClass.any = { Closure predicate -> arrayValues(delegate).any(predicate) }
            metaClass.asCollection = { -> arrayValues(delegate) }
            metaClass.asList = { -> arrayValues(delegate) }
            metaClass.count = { Closure predicate -> arrayValues(delegate).count(predicate) }
            metaClass.eachWithIndex = { Closure consumer ->
                arrayValues(delegate).eachWithIndex(consumer)
                delegate
            }
            metaClass.every = { Closure predicate -> arrayValues(delegate).every(predicate) }
            metaClass.findResults = { Closure mapper -> arrayValues(delegate).findResults(mapper) }
            metaClass.groupBy = { Closure mapper -> arrayValues(delegate).groupBy(mapper) }
            metaClass.join = { String separator -> arrayValues(delegate).join(separator) }
            metaClass.sum = { -> arrayValues(delegate).sum() }
            metaClass.sum = { Closure mapper -> arrayValues(delegate).sum(mapper) }
            metaClass.collect = { Closure mapper -> arrayValues(delegate).collect(mapper) }
            metaClass.collect = { Collection destination, Closure mapper ->
                arrayValues(delegate).collect(destination, mapper)
            }
            metaClass.find = { Closure predicate -> arrayValues(delegate).find(predicate) }
            metaClass.findAll = { Closure predicate -> arrayValues(delegate).findAll(predicate) }
            metaClass.findResult = { Closure mapper -> arrayValues(delegate).findResult(mapper) }
            metaClass.findResult = { Object defaultResult, Closure mapper ->
                arrayValues(delegate).findResult(defaultResult, mapper)
            }
            metaClass.split = { Closure predicate -> arrayValues(delegate).split(predicate) }
        }
    }

    private static void installMiscellaneous() {
        Matcher.metaClass.namedGroup = { String name -> delegate.group(name) }
        TemporalAccessor.metaClass.toEpochMilli = { -> epochMillis(delegate) }
        TemporalAccessor.metaClass.getMillis = { -> epochMillis(delegate) }
        ZonedDateTime.metaClass.getDayOfWeekEnum = { -> delegate.dayOfWeek }

        Map<String, String> removedDateMethods = [
            getCenturyOfEra: '[getCenturyOfEra] is no longer available; use [get(ChronoField.YEAR_OF_ERA) / 100] instead',
            getEra: '[getEra] is no longer available; use [get(ChronoField.ERA)] instead',
            getHourOfDay: '[getHourOfDay] is no longer available; use [getHour()] instead',
            getMillisOfDay: '[getMillisOfDay] is no longer available; use [get(ChronoField.MILLI_OF_DAY)] instead',
            getMillisOfSecond: '[getMillisOfSecond] is no longer available; use [get(ChronoField.MILLI_OF_SECOND)] instead',
            getMinuteOfDay: '[getMinuteOfDay] is no longer available; use [get(ChronoField.MINUTE_OF_DAY)] instead',
            getMinuteOfHour: '[getMinuteOfHour] is no longer available; use [getMinute()] instead',
            getMonthOfYear: '[getMonthOfYear] is no longer available; use [getMonthValue()] instead',
            getSecondOfDay: '[getSecondOfDay] is no longer available; use [get(ChronoField.SECOND_OF_DAY)] instead',
            getSecondOfMinute: '[getSecondOfMinute] is no longer available; use [getSecond()] instead',
            getWeekOfWeekyear: '[getWeekOfWeekyear] is no longer available; use [get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)] instead',
            getWeekyear: '[getWeekyear] is no longer available; use [get(IsoFields.WEEK_BASED_YEAR)] instead',
            getYearOfCentury: '[getYearOfCentury] is no longer available; use [get(ChronoField.YEAR_OF_ERA) % 100] instead',
            getYearOfEra: '[getYearOfEra] is no longer available; use [get(ChronoField.YEAR_OF_ERA)] instead'
        ]
        removedDateMethods.each { String methodName, String message ->
            ZonedDateTime.metaClass."$methodName" = { -> throw new UnsupportedOperationException(message) }
        }
    }

    private static List copyIterable(Iterable receiver) {
        List result = []
        for (def value : receiver) {
            result.add(value)
        }
        result
    }

    private static List arrayValues(Object receiver) {
        int length = Array.getLength(receiver)
        List result = new ArrayList(length)
        for (int index = 0; index < length; index++) {
            result.add(Array.get(receiver, index))
        }
        result
    }

    private static Object callMapClosure(Closure closure, Map.Entry entry) {
        closure.maximumNumberOfParameters == 1 ? closure.call(entry) : closure.call(entry.key, entry.value)
    }

    private static Object findCollectionResult(Collection receiver, Object defaultResult, Closure mapper) {
        for (def value : receiver) {
            def result = mapper.call(value)
            if (result != null) {
                return result
            }
        }
        defaultResult
    }

    private static Object findMapResult(Map receiver, Object defaultResult, Closure mapper) {
        for (Map.Entry entry : receiver.entrySet()) {
            def result = callMapClosure(mapper, entry)
            if (result != null) {
                return result
            }
        }
        defaultResult
    }

    private static Map newSimilarMap(Map receiver) {
        receiver instanceof TreeMap ? new TreeMap() : new LinkedHashMap()
    }

    private static String[] splitOnToken(String receiver, String token, int limit) {
        if (!receiver || !token || receiver.length() < token.length()) {
            return [receiver] as String[]
        }

        List<String> result = []
        int position = 0
        for (; limit != 1; limit--) {
            int index = receiver.indexOf(token, position)
            if (index == -1) {
                break
            }
            result.add(receiver.substring(position, index))
            position = index + token.length()
        }
        result.add(receiver.substring(position))
        result as String[]
    }

    private static Object getByPath(Object receiver, String path, boolean hasDefault, Object defaultValue) {
        String[] elements = splitPath(path)
        Object current = receiver
        for (int index = 0; index < elements.length; index++) {
            String element = elements[index]
            boolean terminal = index == elements.length - 1
            if (!element) {
                throw new IllegalArgumentException("Extra '.' in path [${path}] at index [${index}]")
            }

            if (current instanceof Map) {
                if (current.containsKey(element)) {
                    current = current.get(element)
                    continue
                }
            } else if (current instanceof List) {
                int listIndex
                try {
                    listIndex = Integer.parseInt(element)
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException(
                        "Could not parse [${element}] as a int index into list at path [${path}] and index [${index}]",
                        error
                    )
                }
                if (current.size() >= listIndex) {
                    current = current.get(listIndex)
                    continue
                }
            } else {
                throw new IllegalArgumentException(
                    "Non-container [${current.getClass().name}] at [${element}], index [${index}] in path [${path}]"
                )
            }

            if (terminal) {
                if (hasDefault) {
                    return defaultValue
                }
                throw new IllegalArgumentException("Could not find value at path [${path}]")
            }
            throw new IllegalArgumentException(
                "Container does not have [${element}], for non-terminal index [${index}] in path [${path}]"
            )
        }
        current
    }

    private static String[] splitPath(String path) {
        if (path.length() == 0) {
            throw new IllegalArgumentException('Missing path')
        }
        if (path.endsWith('.')) {
            throw new IllegalArgumentException("Trailing '.' in path [${path}]")
        }
        path.split('\\.')
    }

    private static String digest(String source, String algorithm) {
        StringBuilder result = new StringBuilder()
        for (byte value : MessageDigest.getInstance(algorithm).digest(source.getBytes(StandardCharsets.UTF_8))) {
            String hex = Integer.toHexString(value & 0xff)
            if (hex.length() == 1) {
                result.append('0')
            }
            result.append(hex)
        }
        result.toString()
    }

    private static long epochMillis(TemporalAccessor receiver) {
        receiver.getLong(ChronoField.INSTANT_SECONDS) * 1000L +
            receiver.get(ChronoField.NANO_OF_SECOND).intdiv(1_000_000)
    }
}

/** Enforces the merged Painless whitelist while a context closure executes. */
class PainlessWhitelistGuard {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial { false }
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial { false }
    private static final Map<String, Set<String>> INSTANCE_METHODS = [:]
    private static final Map<String, Set<String>> STATIC_METHODS = [:]
    private static final Map<String, Set<String>> READABLE_PROPERTIES = [:]
    private static final Map<String, Set<String>> WRITABLE_PROPERTIES = [:]
    private static boolean installed

    static synchronized void install(File whitelist) {
        if (installed) {
            return
        }
        Map parsed = new JsonSlurper().parse(whitelist) as Map
        for (Map type : parsed.classes as List<Map>) {
            String name = type.name
            INSTANCE_METHODS.put(name, methodNames(type.methods as List<Map>))
            STATIC_METHODS.put(name, methodNames(type.static_methods as List<Map>))

            Set<String> readable = fieldNames(type.fields as List<Map>)
            Set<String> writable = fieldNames(type.fields as List<Map>)
            for (Map method : type.methods as List<Map>) {
                String methodName = method.name
                List parameters = method.parameters as List
                if (parameters.isEmpty() && methodName.startsWith('get') && methodName.length() > 3) {
                    readable.add(decapitalize(methodName.substring(3)))
                }
                if (parameters.isEmpty() && methodName.startsWith('is') && methodName.length() > 2) {
                    readable.add(decapitalize(methodName.substring(2)))
                }
                if (parameters.size() == 1 && methodName.startsWith('set') && methodName.length() > 3) {
                    writable.add(decapitalize(methodName.substring(3)))
                }
            }
            READABLE_PROPERTIES.put(name, readable)
            WRITABLE_PROPERTIES.put(name, writable)
        }

        Map<Class, String> targets = [
            (String): 'String',
            (StringBuilder): 'CharSequence',
            (GStringImpl): 'String',
            (ArrayList): 'ArrayList',
            (HashMap): 'HashMap',
            (LinkedHashMap): 'LinkedHashMap'
        ]
        targets.each { Class target, String alias ->
            MetaClass original = GroovySystem.metaClassRegistry.getMetaClass(target)
            PainlessGuardMetaClass guarded = new PainlessGuardMetaClass(original, alias)
            guarded.initialize()
            GroovySystem.metaClassRegistry.setMetaClass(target, guarded)
        }
        installed = true
    }

    static <T> T active(Closure<T> action) {
        boolean previous = ACTIVE.get()
        ACTIVE.set(true)
        try {
            action.call()
        } finally {
            ACTIVE.set(previous)
        }
    }

    static boolean isActive() {
        ACTIVE.get() && !CHECKING.get()
    }

    static <T> T checking(Closure<T> action) {
        boolean previous = CHECKING.get()
        CHECKING.set(true)
        try {
            action.call()
        } finally {
            CHECKING.set(previous)
        }
    }

    static boolean allowsMethod(String alias, String name, boolean staticCall) {
        Map<String, Set<String>> index = staticCall ? STATIC_METHODS : INSTANCE_METHODS
        (index.get(alias) ?: Collections.emptySet()).contains(name)
    }

    static boolean allowsRead(String alias, Object receiver, String property) {
        if (receiver instanceof Map && receiver.containsKey(property)) {
            return true
        }
        property == 'class' || (READABLE_PROPERTIES.get(alias) ?: Collections.emptySet()).contains(property)
    }

    static boolean allowsWrite(String alias, Object receiver, String property) {
        receiver instanceof Map || (WRITABLE_PROPERTIES.get(alias) ?: Collections.emptySet()).contains(property)
    }

    private static Set<String> methodNames(List<Map> methods) {
        new LinkedHashSet<String>(methods.collect { it.name as String })
    }

    private static Set<String> fieldNames(List<Map> fields) {
        new LinkedHashSet<String>(fields.collect { it.name as String })
    }

    private static String decapitalize(String value) {
        value[0].toLowerCase() + value.substring(1)
    }
}

class PainlessGuardMetaClass extends DelegatingMetaClass {
    private final String alias

    PainlessGuardMetaClass(MetaClass delegate, String alias) {
        super(delegate)
        this.alias = alias
    }

    @Override
    Object invokeMethod(Object receiver, String name, Object[] arguments) {
        boolean allowed = !PainlessWhitelistGuard.isActive() || PainlessWhitelistGuard.checking {
            PainlessWhitelistGuard.allowsMethod(alias, name, false)
        }
        if (!allowed) {
            Class receiverType = PainlessWhitelistGuard.checking { receiver.getClass() }
            throw new MissingMethodException(name, receiverType, arguments)
        }
        super.invokeMethod(receiver, name, arguments)
    }

    @Override
    Object invokeStaticMethod(Object receiver, String name, Object[] arguments) {
        boolean allowed = !PainlessWhitelistGuard.isActive() || PainlessWhitelistGuard.checking {
            PainlessWhitelistGuard.allowsMethod(alias, name, true)
        }
        if (!allowed) {
            throw new MissingMethodException(name, receiver as Class, arguments)
        }
        super.invokeStaticMethod(receiver, name, arguments)
    }

    @Override
    Object getProperty(Object receiver, String property) {
        boolean allowed = !PainlessWhitelistGuard.isActive() || PainlessWhitelistGuard.checking {
            PainlessWhitelistGuard.allowsRead(alias, receiver, property)
        }
        if (!allowed) {
            Class receiverType = PainlessWhitelistGuard.checking { receiver.getClass() }
            throw new MissingPropertyException(property, receiverType)
        }
        super.getProperty(receiver, property)
    }

    @Override
    void setProperty(Object receiver, String property, Object value) {
        boolean allowed = !PainlessWhitelistGuard.isActive() || PainlessWhitelistGuard.checking {
            PainlessWhitelistGuard.allowsWrite(alias, receiver, property)
        }
        if (!allowed) {
            Class receiverType = PainlessWhitelistGuard.checking { receiver.getClass() }
            throw new MissingPropertyException(property, receiverType)
        }
        super.setProperty(receiver, property, value)
    }
}

/** Script-visible field values compatible with doc['field'].value and list access. */
class PainlessFieldValues extends ArrayList {
    PainlessFieldValues(Collection values) {
        super(values ?: [])
    }

    Object getValue() {
        if (isEmpty()) {
            throw new IllegalStateException("A document doesn't have a value for a field")
        }
        get(0)
    }
}

/** Result of one synthetic Painless context execution. */
class PainlessContextResult {
    Object value
    List emitted
    Map globals
}

/** Minimal grok/dissect result facade used by runtime field scripts. */
class PainlessExtractor {
    private final Closure extractor

    PainlessExtractor(Closure extractor) {
        this.extractor = extractor
    }

    Map extract(String input) {
        extractor.call(input) as Map
    }
}

/** Delegate that exposes only globals allowed by a selected Painless context. */
class PainlessContextScope extends GroovyObjectSupport {
    private final Map<String, Object> globals
    private final Set<String> allowed
    final List emitted = []

    PainlessContextScope(Set<String> allowed, Map<String, Object> globals) {
        this.allowed = allowed
        this.globals = globals
    }

    Object propertyMissing(String name) {
        if (!allowed.contains(name)) {
            throw new MissingPropertyException(name, getClass())
        }
        globals.get(name)
    }

    void propertyMissing(String name, Object value) {
        if (allowed.contains(name)) {
            throw new ReadOnlyPropertyException(name, getClass())
        }
        throw new MissingPropertyException(name, getClass())
    }

    void emit(Object value) {
        if (value == null) {
            throw new IllegalArgumentException('emit cannot accept null')
        }
        emitted.add(value)
    }

    void emit(double latitude, double longitude) {
        emitted.add([lat: latitude, lon: longitude])
    }

    PainlessExtractor grok(String expression) {
        List<String> names = []
        String regex = expression.replaceAll(/%\{[^}:]+:([^}]+)\}/) { Object[] match ->
            names.add(match[1] as String)
            '(.*?)'
        }
        Pattern pattern = Pattern.compile(regex)
        new PainlessExtractor({ String input ->
            Matcher matcher = pattern.matcher(input)
            if (!matcher.matches()) {
                return [:]
            }
            Map result = new LinkedHashMap()
            names.eachWithIndex { String name, int index -> result.put(name, matcher.group(index + 1)) }
            result
        })
    }

    PainlessExtractor dissect(String expression) {
        List<String> names = []
        StringBuilder regex = new StringBuilder('^')
        Matcher matcher = Pattern.compile(/%\{([^}]+)\}/).matcher(expression)
        int position = 0
        while (matcher.find()) {
            regex.append(Pattern.quote(expression.substring(position, matcher.start())))
            regex.append('(.*?)')
            names.add(matcher.group(1))
            position = matcher.end()
        }
        regex.append(Pattern.quote(expression.substring(position))).append('$')
        Pattern pattern = Pattern.compile(regex.toString())
        new PainlessExtractor({ String input ->
            Matcher inputMatcher = pattern.matcher(input)
            if (!inputMatcher.matches()) {
                return [:]
            }
            Map result = new LinkedHashMap()
            names.eachWithIndex { String name, int index -> result.put(name, inputMatcher.group(index + 1)) }
            result
        })
    }
}

/** Executes closures with globals matching Elasticsearch Painless contexts. */
class PainlessContexts {
    private static final Map<String, Map> CONTEXTS = [
        runtime: [globals: ['params', 'doc'], returns: 'void', emit: true],
        field: [globals: ['params', 'doc'], returns: 'object'],
        ingest: [globals: ['params', 'ctx'], returns: 'void'],
        filter: [globals: ['params', 'doc'], returns: 'boolean'],
        score: [globals: ['params', 'doc', '_score'], returns: 'double'],
        sort: [globals: ['params', 'doc', '_score'], returns: 'sort'],
        update: [globals: ['params', 'ctx'], returns: 'void'],
        update_by_query: [globals: ['params', 'ctx'], returns: 'void'],
        reindex: [globals: ['params', 'ctx'], returns: 'void'],
        similarity: [globals: ['weight', 'query', 'field', 'term', 'doc'], returns: 'double'],
        weight: [globals: ['query', 'field', 'term'], returns: 'double'],
        minimum_should_match: [globals: ['params', 'doc'], returns: 'int'],
        metric_agg_init: [globals: ['params', 'state'], returns: 'void'],
        metric_agg_map: [globals: ['params', 'state', 'doc', '_score'], returns: 'void'],
        metric_agg_combine: [globals: ['params', 'state'], returns: 'serializable'],
        metric_agg_reduce: [globals: ['params', 'states'], returns: 'serializable'],
        bucket_script: [globals: ['params'], returns: 'number'],
        bucket_selector: [globals: ['params'], returns: 'boolean'],
        watcher_condition: [globals: ['params', 'ctx'], returns: 'boolean'],
        watcher_transform: [globals: ['params', 'ctx'], returns: 'object'],
        analysis_predicate: [globals: ['params', 'token'], returns: 'boolean']
    ].asImmutable()

    private static final Map<String, String> ALIASES = [
        'runtime_field': 'runtime',
        'ingest_processor': 'ingest',
        'update-by-query': 'update_by_query',
        'minimum-should-match': 'minimum_should_match',
        'metric-agg-init': 'metric_agg_init',
        'metric-agg-map': 'metric_agg_map',
        'metric-agg-combine': 'metric_agg_combine',
        'metric-agg-reduce': 'metric_agg_reduce',
        'bucket-script': 'bucket_script',
        'bucket-selector': 'bucket_selector',
        'watcher-condition': 'watcher_condition',
        'watcher-transform': 'watcher_transform',
        'analysis': 'analysis_predicate'
    ].asImmutable()

    static Set<String> names() {
        Collections.unmodifiableSet(new LinkedHashSet<String>(CONTEXTS.keySet()))
    }

    static PainlessContextResult execute(String contextName, Map supplied = [:], Closure script) {
        String name = ALIASES.containsKey(contextName) ? ALIASES.get(contextName) : contextName
        Map definition = CONTEXTS.get(name)
        if (definition == null) {
            throw new IllegalArgumentException("Unknown Painless context [${contextName}]")
        }

        Map<String, Object> globals = prepareGlobals(definition.globals as List<String>, supplied)
        PainlessContextScope scope = new PainlessContextScope(
            new LinkedHashSet<String>(definition.globals as Collection<String>),
            globals
        )
        Closure executable = (Closure) script.rehydrate(scope, script.owner, script.thisObject)
        executable.resolveStrategy = Closure.DELEGATE_FIRST
        Object value = PainlessWhitelistGuard.active { executable.call() }
        validateReturn(name, definition.returns as String, value)
        new PainlessContextResult(
            value: definition.returns == 'void' ? null : value,
            emitted: Collections.unmodifiableList(new ArrayList(scope.emitted)),
            globals: Collections.unmodifiableMap(globals)
        )
    }

    private static Map<String, Object> prepareGlobals(List<String> names, Map supplied) {
        Map<String, Object> result = new LinkedHashMap()
        for (String name : names) {
            Object value
            if (supplied.containsKey(name)) {
                value = supplied.get(name)
            } else if (name == 'params' || name == 'ctx' || name == 'state') {
                value = new LinkedHashMap()
            } else if (name == 'states') {
                value = []
            } else if (name == 'doc') {
                value = [:]
            } else if (name == '_score' || name == 'weight') {
                value = 0d
            } else {
                throw new IllegalArgumentException("Missing required context global [${name}]")
            }
            result.put(name, name == 'doc' ? prepareDoc(value as Map) : value)
        }
        result
    }

    private static Map prepareDoc(Map doc) {
        Map result = new LinkedHashMap()
        doc.each { key, value ->
            Collection values = value instanceof Collection ? value as Collection : [value]
            result.put(key, value instanceof PainlessFieldValues ? value : new PainlessFieldValues(values))
        }
        result.withDefault { new PainlessFieldValues([]) }
    }

    private static void validateReturn(String context, String type, Object value) {
        boolean valid
        switch (type) {
            case 'void': valid = true; break
            case 'object': valid = true; break
            case 'boolean': valid = value instanceof Boolean; break
            case 'double': valid = value instanceof Number; break
            case 'int': valid = value instanceof Number && (value as Number).longValue() == (value as Number).intValue(); break
            case 'number': valid = value instanceof Number; break
            case 'sort': valid = value instanceof Number || value instanceof String; break
            case 'serializable': valid = isSerializableValue(value); break
            default: valid = false
        }
        if (!valid) {
            throw new ClassCastException("Context [${context}] requires return type [${type}], got [${value?.getClass()?.name ?: 'null'}]")
        }
    }

    private static boolean isSerializableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return true
        }
        if (value instanceof List) {
            return value.every { isSerializableValue(it) }
        }
        if (value instanceof Map) {
            return value.every { key, item -> key instanceof String && isSerializableValue(item) }
        }
        false
    }
}

PainlessAugmentation.install()
PainlessWhitelistGuard.install(new File(gradle.startParameter.currentDir, 'painless-whitelist-lenient.json'))
gradle.ext.PainlessContexts = PainlessContexts

println "Installed Painless augmentations and ${PainlessContexts.names().size()} contexts on Groovy ${GroovySystem.version} / Java ${System.getProperty('java.version')}"
