package de.bwaldvogel.mongo.backend;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.bson.ObjectId;
import de.bwaldvogel.mongo.exception.BadValueException;
import de.bwaldvogel.mongo.exception.DollarPrefixedFieldNameException;
import de.bwaldvogel.mongo.exception.ErrorCode;
import de.bwaldvogel.mongo.exception.MongoServerError;
import de.bwaldvogel.mongo.exception.MongoServerException;
import de.bwaldvogel.mongo.exception.PathNotViableException;
import de.bwaldvogel.mongo.wire.bson.BsonEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class Utils {

    public static final String PATH_DELIMITER = ".";
    private static final Pattern PATH_DELIMITER_PATTERN = Pattern.compile(Pattern.quote(PATH_DELIMITER));

    static void validateKey(String key) {
        if (key.endsWith(PATH_DELIMITER)) {
            throw new MongoServerError(ErrorCode._40353, "FieldPath must not end with a '.'.");
        }
        if (key.startsWith(PATH_DELIMITER) || key.contains("..")) {
            throw new MongoServerError(ErrorCode._15998, "FieldPath field names may not be empty strings.");
        }
    }

    public static Object getSubdocumentValue(Document document, String key) {
        return getSubdocumentValue(document, key, false);
    }

    public static Object getSubdocumentValueCollectionAware(Document document, String key) {
        return getSubdocumentValue(document, key, true);
    }

    private static Object getSubdocumentValue(Document document, String key, boolean handleCollections) {
        validateKey(key);
        List<String> pathFragments = splitPath(key);
        if (pathFragments.size() == 1) {
            return getFieldValueListSafe(document, CollectionUtils.getSingleElement(pathFragments));
        }
        String mainKey = pathFragments.get(0);
        String subKey = joinTail(pathFragments);
        Assert.doesNotStartWith(subKey, "$.");
        Object subObject = getFieldValueListSafe(document, mainKey);
        if (subObject instanceof Document) {
            return getSubdocumentValue((Document) subObject, subKey, handleCollections);
        } else if (handleCollections && subObject instanceof Collection) {
            Collection<?> values = (Collection<?>) subObject;
            List<Object> result = new ArrayList<>();
            for (Object o : values) {
                if (o instanceof Document) {
                    Object subdocumentValue = getSubdocumentValue((Document) o, subKey, handleCollections);
                    if (subdocumentValue instanceof Collection) {
                        result.addAll((Collection<?>) subdocumentValue);
                    } else {
                        result.add(subdocumentValue);
                    }
                } else {
                    result.add(Missing.getInstance());
                }
            }
            return result;
        } else {
            return Missing.getInstance();
        }
    }

    public static String getDatabaseNameFromFullName(String fullName) {
        return firstFragment(fullName);
    }

    public static String getCollectionNameFromFullName(String fullName) {
        return fullName.substring(fullName.indexOf(PATH_DELIMITER) + 1);
    }

    public static boolean isTrue(Object value) {
        if (Missing.isNullOrMissing(value)) {
            return false;
        }

        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }

        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }

        return true;
    }

    static Object normalizeValue(Object value) {
        if (Missing.isNullOrMissing(value)) {
            return null;
        }
        if (value instanceof Long && cannotBeRepresentedAsDouble((Long) value)) {
            return value;
        } else if (value instanceof Number) {
            double doubleValue = ((Number) value).doubleValue();
            if (doubleValue == -0.0) {
                doubleValue = 0.0;
            }
            return Double.valueOf(doubleValue);
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            Document result = new Document();
            for (Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            return result;
        } else if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            return collection.stream()
                .map(Utils::normalizeValue)
                .collect(Collectors.toList());
        } else {
            return value;
        }
    }

    private static boolean cannotBeRepresentedAsDouble(Long value) {
        return value.longValue() != (long) value.doubleValue();
    }

    public static Number normalizeNumber(Number value) {
        if (value == null) {
            return null;
        }

        double doubleValue = value.doubleValue();
        if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
            return Double.valueOf(doubleValue);
        } else if (value.intValue() == doubleValue) {
            return value.intValue();
        } else if (value.longValue() == doubleValue) {
            return value.longValue();
        } else {
            return Double.valueOf(doubleValue);
        }
    }

    static boolean nullAwareEquals(Object a, Object b) {
        if (a == b) {
            return true;
        } else if (Missing.isNullOrMissing(a) && Missing.isNullOrMissing(b)) {
            return true;
        } else if (Missing.isNullOrMissing(a) || Missing.isNullOrMissing(b)) {
            return false;
        } else {
            Object normalizedA = normalizeValue(a);
            Object normalizedB = normalizeValue(b);
            return Objects.equals(normalizedA, normalizedB);
        }
    }

    static int calculateSize(Document document) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            BsonEncoder.encodeDocument(document, buffer);
            return buffer.writerIndex();
        } catch (RuntimeException e) {
            throw new MongoServerException("Failed to calculate document size", e);
        } finally {
            buffer.release();
        }
    }

    static boolean containsQueryExpression(Object value) {
        if (value == null) {
            return false;
        }

        if (!(value instanceof Document)) {
            return false;
        }

        Document doc = (Document) value;
        for (String key : doc.keySet()) {
            if (key.startsWith("$")) {
                return true;
            }
            if (containsQueryExpression(doc.get(key))) {
                return true;
            }
        }
        return false;
    }

    static Object getFieldValueListSafe(Object value, String field) throws IllegalArgumentException {
        if (Missing.isNullOrMissing(value)) {
            return Missing.getInstance();
        }

        if (field.equals("$") || field.contains(PATH_DELIMITER)) {
            throw new IllegalArgumentException("illegal field: " + field);
        }

        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            if (isNumeric(field)) {
                int pos = Integer.parseInt(field);
                if (pos >= 0 && pos < list.size()) {
                    return list.get(pos);
                } else {
                    return Missing.getInstance();
                }
            } else {
                List<Object> values = new ArrayList<>();
                for (Object subValue : list) {
                    if (subValue instanceof Document) {
                        Object subDocumentValue = ((Document) subValue).getOrMissing(field);
                        if (!(subDocumentValue instanceof Missing)) {
                            values.add(subDocumentValue);
                        }
                    }
                }
                if (values.isEmpty()) {
                    return Missing.getInstance();
                }
                return values;
            }
        } else if (value instanceof Document) {
            Document document = (Document) value;
            return document.getOrMissing(field);
        } else {
            return Missing.getInstance();
        }
    }

    private static boolean isNumeric(String value) {
        return value.chars().allMatch(Character::isDigit);
    }

    static boolean hasSubdocumentValue(Object document, String key) {
        List<String> pathFragments = splitPath(key);
        String mainKey = pathFragments.get(0);
        if (pathFragments.size() == 1) {
            return hasFieldValueListSafe(document, key);
        }
        String subKey = getSubkey(pathFragments, new AtomicReference<>());
        Object subObject = getFieldValueListSafe(document, mainKey);
        if (subObject instanceof Document || subObject instanceof List<?>) {
            return hasSubdocumentValue(subObject, subKey);
        } else {
            return false;
        }
    }

    static boolean canFullyTraverseSubkeyForRename(Object document, String key) {
        List<String> pathFragments = splitPath(key);
        String mainKey = pathFragments.get(0);
        if (pathFragments.size() == 1) {
            return true;
        }
        String subKey = getSubkey(pathFragments, new AtomicReference<>());

        Object subObject = getFieldValueListSafe(document, mainKey);
        if (subObject instanceof Document) {
            return canFullyTraverseSubkeyForRename(subObject, subKey);
        } else {
            return subObject instanceof Missing;
        }
    }

    static String getSubkey(List<String> pathFragments, AtomicReference<Integer> matchPos) {
        String key = joinPath(pathFragments);
        if (key.matches(".*\\$(\\.).+\\$(\\.).*")) {
            throw new BadValueException("Too many positional (i.e. '$') elements found in path '" + key + "'");
        }

        String subKey = joinTail(pathFragments);
        if (subKey.matches("\\$(\\..+)?")) {
            if (matchPos == null || matchPos.get() == null) {
                throw new BadValueException("The positional operator did not find the match needed from the query.");
            }
            Integer pos = matchPos.getAndSet(null);
            return subKey.replaceFirst("\\$", String.valueOf(pos));
        }
        return subKey;
    }

    static boolean hasFieldValueListSafe(Object document, String field) throws IllegalArgumentException {
        if (document == null) {
            return false;
        }

        if (field.equals("$") || field.contains(PATH_DELIMITER)) {
            throw new IllegalArgumentException("illegal field: " + field);
        }

        if (document instanceof List<?>) {
            if (isNumeric(field)) {
                int pos = Integer.parseInt(field);
                List<?> list = (List<?>) document;
                return (pos >= 0 && pos < list.size());
            } else {
                return false;
            }
        } else if (document instanceof Document) {
            return ((Document) document).containsKey(field);
        }

        throw new IllegalArgumentException("illegal document: " + document);
    }

    public static void markOkay(Document result) {
        result.put("ok", 1.0);
    }

    private static void setListSafe(Object document, String key, String previousKey, Object obj) {
        if (document instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> list = ((List<Object>) document);
            if (!isNumeric(key)) {
                String element = new Document(previousKey, document).toString(true);
                throw new PathNotViableException("Cannot create field '" + key + "' in element " + element);
            }
            int pos = Integer.parseInt(key);
            while (list.size() <= pos) {
                list.add(null);
            }
            list.set(pos, obj);
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> documentAsMap = (Map<String, Object>) document;
            documentAsMap.put(key, obj);
        }
    }

    private static Object removeListSafe(Object value, String key) {
        if (value instanceof Document) {
            Document document = (Document) value;
            if (document.containsKey(key)) {
                return document.remove(key);
            }
            return Missing.getInstance();
        } else if (value instanceof List<?>) {
            List<?> values = ((List<?>) value);
            if (isNumeric(key)) {
                int pos = Integer.parseInt(key);
                if (values.size() > pos) {
                    return values.set(pos, null);
                } else {
                    return Missing.getInstance();
                }
            } else {
                List<Object> removedValues = new ArrayList<>();
                for (Object subValue : values) {
                    if (subValue instanceof Document) {
                        Object removedValue = removeListSafe(subValue, key);
                        if (!(removedValue instanceof Missing)) {
                            removedValues.add(removedValue);
                        }
                    } else if (subValue instanceof List) {
                        List<?> subValueList = (List<?>) subValue;
                        for (Object subValueListValue : subValueList) {
                            Object removedValue = removeListSafe(subValueListValue, key);
                            if (!(removedValue instanceof Missing)) {
                                removedValues.add(removedValue);
                            }
                        }
                    }
                }
                return removedValues;
            }
        } else {
            return Missing.getInstance();
        }
    }

    public static String join(List<?> values, String delimiter) {
        return values.stream()
            .map(Object::toString)
            .collect(Collectors.joining(delimiter));
    }

    static void changeSubdocumentValue(Object document, String key, Object newValue, Integer matchPos) {
        changeSubdocumentValue(document, key, newValue, new AtomicReference<>(matchPos));
    }

    public static void changeSubdocumentValue(Object document, String key, Object newValue) {
        changeSubdocumentValue(document, key, newValue, new AtomicReference<>());
    }

    static void changeSubdocumentValue(Object document, String key, Object newValue, AtomicReference<Integer> matchPos) {
        changeSubdocumentValue(document, key, newValue, null, matchPos);
    }

    private static void changeSubdocumentValue(Object document, String key, Object newValue, String previousKey, AtomicReference<Integer> matchPos) {
        List<String> pathFragments = splitPath(key);
        String mainKey = pathFragments.get(0);
        if (pathFragments.size() == 1) {
            setListSafe(document, key, previousKey, newValue);
            return;
        }
        String subKey = getSubkey(pathFragments, matchPos);
        Object subObject = getFieldValueListSafe(document, mainKey);
        if (subObject instanceof Document || subObject instanceof List<?>) {
            changeSubdocumentValue(subObject, subKey, newValue, mainKey, matchPos);
        } else if (Missing.isNeitherNullNorMissing(subObject)) {
            String element = new Document(mainKey, subObject).toString(true);
            String subKeyFirst = splitPath(subKey).get(0);
            throw new PathNotViableException("Cannot create field '" + subKeyFirst + "' in element " + element);
        } else {
            Document obj = new Document();
            changeSubdocumentValue(obj, subKey, newValue, mainKey, matchPos);
            setListSafe(document, mainKey, previousKey, obj);
        }
    }

    public static void validateFieldNames(Document document) {
        validateFieldNames(document, null);
    }

    private static void validateFieldNames(Object value, String path) {
        if (value instanceof Document) {
            Document document = (Document) value;
            for (Entry<String, Object> entry : document.entrySet()) {
                String key = entry.getKey();
                String nextPath = path != null ? path + "." + key : key;
                if (key.startsWith("$") && !Constants.REFERENCE_KEYS.contains(key)) {
                    throw new DollarPrefixedFieldNameException("The dollar ($) prefixed field '" + key + "' in '" + nextPath + "' is not allowed in the context of an update's replacement document. Consider using an aggregation pipeline with $replaceWith.");
                }
            }
        } else if (value instanceof Collection<?>) {
            Collection<?> values = (Collection<?>) value;
            for (Object object : values) {
                validateFieldNames(object, path + ".");
            }
        }
    }

    static Object removeSubdocumentValue(Object document, String key, Integer matchPos) {
        return removeSubdocumentValue(document, key, new AtomicReference<>(matchPos));
    }

    public static Object removeSubdocumentValue(Object document, String key) {
        return removeSubdocumentValue(document, key, new AtomicReference<>());
    }

    private static Object removeSubdocumentValue(Object document, String key, AtomicReference<Integer> matchPos) {
        List<String> pathFragments = splitPath(key);
        String mainKey = pathFragments.get(0);
        if (pathFragments.size() == 1) {
            return removeListSafe(document, key);
        }
        String subKey = getSubkey(pathFragments, matchPos);
        Assert.notNullOrEmpty(subKey);

        Object subObject = getFieldValueListSafe(document, mainKey);
        if (subObject instanceof Document || subObject instanceof List<?>) {
            return removeSubdocumentValue(subObject, subKey, matchPos);
        } else {
            return Missing.getInstance();
        }
    }

    public static String describeType(Object value) {
        if (value == null) {
            return "null";
        } else {
            return describeType(value.getClass());
        }
    }

    public static String describeType(Class<?> type) {
        if (Missing.class.isAssignableFrom(type)) {
            return "missing";
        } else if (Document.class.isAssignableFrom(type)) {
            return "object";
        } else if (String.class.isAssignableFrom(type)) {
            return "string";
        } else if (Collection.class.isAssignableFrom(type)) {
            return "array";
        } else if (Integer.class.isAssignableFrom(type)) {
            return "int";
        } else if (Long.class.isAssignableFrom(type)) {
            return "long";
        } else if (Double.class.isAssignableFrom(type)) {
            return "double";
        } else if (ObjectId.class.isAssignableFrom(type)) {
            return "objectId";
        } else if (Instant.class.isAssignableFrom(type)) {
            return "date";
        } else {
            return type.getName();
        }
    }

    static Document firstBatchCursorResponse(String ns, Document... documents) {
        return firstBatchCursorResponse(ns, Arrays.asList(documents));
    }

    static Document firstBatchCursorResponse(String ns, Iterable<Document> documents) {
        List<Document> firstBatch = new ArrayList<>();
        for (Document document : documents) {
            firstBatch.add(document);
        }
        return firstBatchCursorResponse(ns, firstBatch);
    }

    static Document firstBatchCursorResponse(String ns, Stream<Document> firstBatch) {
        return firstBatchCursorResponse(ns, firstBatch.collect(Collectors.toList()));
    }

    static Document firstBatchCursorResponse(String ns, List<Document> firstBatch) {
        return firstBatchCursorResponse(ns, firstBatch, EmptyCursor.get());
    }

    static Document firstBatchCursorResponse(String ns, List<Document> firstBatch, Cursor cursor) {
        return firstBatchCursorResponse(ns, firstBatch, cursor.getId());
    }

    static Document firstBatchCursorResponse(String ns, List<Document> firstBatch, long cursorId) {
        return firstBatchCursorResponse(ns, "firstBatch", firstBatch, cursorId);
    }

    static Document nextBatchCursorResponse(String ns, List<Document> nextBatch, long cursorId) {
        return firstBatchCursorResponse(ns, "nextBatch", nextBatch, cursorId);
    }

    private static Document firstBatchCursorResponse(String ns, String key, List<Document> documents, long cursorId) {
        Document cursorResponse = new Document();
        cursorResponse.put("id", cursorId);
        cursorResponse.put("ns", ns);
        cursorResponse.put(key, documents);

        Document response = new Document();
        response.put("cursor", cursorResponse);
        markOkay(response);
        return response;
    }

    static String joinPath(String... fragments) {
        return Stream.of(fragments)
            .filter(fragment -> !fragment.isEmpty())
            .collect(Collectors.joining(PATH_DELIMITER));
    }

    public static String joinTail(List<String> pathFragments) {
        return pathFragments.stream()
            .skip(1)
            .collect(Collectors.joining(PATH_DELIMITER));
    }

    static String joinPath(List<String> fragments) {
        return String.join(PATH_DELIMITER, fragments);
    }

    public static String firstFragment(String input) {
        int delimiterIndex = input.indexOf(PATH_DELIMITER);
        if (delimiterIndex == -1) {
            return input;
        }
        return input.substring(0, delimiterIndex);
    }

    public static List<String> splitPath(String input) {
        return PATH_DELIMITER_PATTERN.splitAsStream(input).collect(Collectors.toList());
    }

    static List<String> getTail(List<String> pathFragments) {
        return pathFragments.subList(1, pathFragments.size());
    }

    public static String getShorterPathIfPrefix(String path1, String path2) {
        if (!path1.startsWith(path2) && !path2.startsWith(path1)) {
            return null;
        }
        List<String> fragments1 = splitPath(path1);
        List<String> fragments2 = splitPath(path2);
        List<String> commonFragments = collectCommonPathFragments(fragments1, fragments2);
        if (commonFragments.size() != fragments1.size() && commonFragments.size() != fragments2.size()) {
            return null;
        }
        return joinPath(commonFragments);
    }

    public static List<String> collectCommonPathFragments(String path1, String path2) {
        List<String> fragments1 = splitPath(path1);
        List<String> fragments2 = splitPath(path2);
        return collectCommonPathFragments(fragments1, fragments2);
    }

    private static List<String> collectCommonPathFragments(List<String> fragments1, List<String> fragments2) {
        List<String> commonFragments = new ArrayList<>();
        for (int i = 0; i < Math.min(fragments1.size(), fragments2.size()); i++) {
            String fragment1 = fragments1.get(i);
            String fragment2 = fragments2.get(i);
            if (fragment1.equals(fragment2)) {
                commonFragments.add(fragment1);
            } else {
                break;
            }
        }
        return commonFragments;
    }

    static String getLastFragment(String path) {
        List<String> fragments = splitPath(path);
        return fragments.get(fragments.size() - 1);
    }

    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return e.toString();
        }
    }

    public static void copySubdocumentValue(Document input, Document result, String key) {
        Object value = getSubdocumentValueCollectionAware(input, key);

        if (!(value instanceof Missing)) {
            changeSubdocumentValue(result, key, value);
        }
    }

}
