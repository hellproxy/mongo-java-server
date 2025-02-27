package de.bwaldvogel.mongo.backend.aggregation.stage;

import static de.bwaldvogel.mongo.TestUtils.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.exception.MongoServerError;

class ProjectStageTest {

    @Test
    void testProject() throws Exception {
        assertThat(project(json("a: 'value'"), json("a: true"))).isEqualTo(json("a: 'value'"));
        assertThat(project(json("_id: 1"), json("a: 1"))).isEqualTo(json("_id: 1"));
        assertThat(project(json("_id: 1, a: 'value'"), json("a: 1"))).isEqualTo(json("_id: 1, a: 'value'"));
        assertThat(project(json("_id: 1, a: 'value'"), json("_id: 0"))).isEqualTo(json("a: 'value'"));
        assertThat(project(json("_id: 1, a: 10, b: 20, c: -30"), json("_id: 0, x: {$abs: '$c'}, b: 1"))).isEqualTo(json("x: 30, b: 20"));
        assertThat(project(json("_id: 1, a: 10, b: 20, c: -30"), json("x: {$abs: '$c'}"))).isEqualTo(json("_id: 1, x: 30"));
        assertThat(project(json("_id: 1, c: -30"), json("x: {y: {$abs: '$c'}}"))).isEqualTo(json("_id: 1, x: {y: 30}"));
        assertThat(project(json("_id: 1, b: 2 c: -30"), json("x: {y: {$multiply: ['$b', {$abs: '$c'}]}}"))).isEqualTo(json("_id: 1, x: {y: 60}"));
        assertThat(project(json("a: [1, 2, 3]"), json("b: {$arrayElemAt: ['$a', 1]}"))).isEqualTo(json("b: 2"));
        assertThat(project(json("a: [{foo: 'bar'}, {foo: 'bas'}, {foo: 'bat'}]"), json("b: {$arrayElemAt: ['$a.foo', 1]}")))
            .isEqualTo(json("b: 'bas'"));
    }

    @Test
    void testProject_withNestedExclusion() throws Exception {
        assertThat(project(json("_id: 1, x: {a: 1, b: 2, c: 3}"), json("'x.b': 0")))
            .isEqualTo(json("_id: 1, x: {a: 1, c: 3}"));
    }

    @Test
    void testProject_withNestedExclusion_array() throws Exception {
        assertThat(project(json("_id: 1, x: [{a: 1, b: 2, c: 3}, {a: 2}]"), json("'x.b': 0")))
            .isEqualTo(json("_id: 1, x: [{a: 1, c: 3}, {a: 2}]"));
    }

    @Test
    void testProject_withNestedInclusion() throws Exception {
        assertThat(project(json("_id: 1, x: {a: 1, b: 2, c: 3}"), json("'x.b': 1, 'x.c': 1, 'y': 1, 'x.d': 1")))
            .isEqualTo(json("_id: 1, x: {b: 2, c: 3}"));
    }

    @Test
    void testProject_withFieldToBeEvaluated() {
        Document projection = new Document();
        projection.put("_id", 1);
        projection.put("x", new Document("count", "$count"));
        assertThat(project(json("_id: 1, count: 5"), projection))
            .isEqualTo(json("_id: 1, x: {count: 5}"));
    }

    @Test
    void testProject_withFieldWithinArrayToBeEvaluated() {
        Document projection = new Document();
        projection.put("_id", 1);
        projection.put("x", Collections.singletonList(new Document("count", "$count")));
        assertThat(project(json("_id: 1, count: 5"), projection))
            .isEqualTo(json("_id: 1, x: [{count: 5}]"));
    }

    private static Document project(Document document, Document projection) {
        return new ProjectStage(projection).projectDocument(document);
    }

    @Test
    void testIllegalProject() throws Exception {
        assertThatExceptionOfType(MongoServerError.class)
            .isThrownBy(() -> new ProjectStage(json("")))
            .withMessage("[Error 40177] specification must have at least one field");
    }

}
