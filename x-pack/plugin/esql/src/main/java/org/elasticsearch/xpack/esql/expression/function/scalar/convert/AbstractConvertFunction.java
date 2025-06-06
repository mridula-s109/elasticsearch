/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import joptsimple.internal.Strings;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.EvalOperator.ExpressionEvaluator;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.UnaryScalarFunction;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isTypeOrUnionType;

/**
 * Base class for functions that converts a field into a function-specific type.
 * <p>
 *     We have a guide for writing these in the javadoc for
 *     {@link org.elasticsearch.xpack.esql.expression.function.scalar}.
 * </p>
 */
public abstract class AbstractConvertFunction extends UnaryScalarFunction implements ConvertFunction {

    // the numeric types convert functions need to handle; the other numeric types are converted upstream to one of these
    private static final List<DataType> NUMERIC_TYPES = List.of(DataType.INTEGER, DataType.LONG, DataType.UNSIGNED_LONG, DataType.DOUBLE);

    protected AbstractConvertFunction(Source source, Expression field) {
        super(source, field);
    }

    protected AbstractConvertFunction(StreamInput in) throws IOException {
        this(Source.readFrom((PlanStreamInput) in), in.readNamedWriteable(Expression.class));
    }

    /**
     * Build the evaluator given the evaluator a multivalued field.
     */
    protected final ExpressionEvaluator.Factory evaluator(ExpressionEvaluator.Factory fieldEval) {
        DataType sourceType = field().dataType().widenSmallNumeric();
        var factory = factories().get(sourceType);
        if (factory == null) {
            throw EsqlIllegalArgumentException.illegalDataType(sourceType);
        }
        return factory.build(source(), fieldEval);
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }
        return isTypeOrUnionType(field(), factories()::containsKey, sourceText(), null, supportedTypesNames(supportedTypes()));
    }

    @Override
    public Set<DataType> supportedTypes() {
        return factories().keySet();
    }

    static String supportedTypesNames(Set<DataType> types) {
        List<String> supportedTypesNames = new ArrayList<>(types.size());
        HashSet<DataType> supportTypes = new HashSet<>(types);
        if (supportTypes.containsAll(NUMERIC_TYPES)) {
            supportedTypesNames.add("numeric");
            NUMERIC_TYPES.forEach(supportTypes::remove);
        }

        if (types.containsAll(DataType.stringTypes())) {
            supportedTypesNames.add("string");
            DataType.stringTypes().forEach(supportTypes::remove);
        }

        supportTypes.forEach(t -> supportedTypesNames.add(t.nameUpper().toLowerCase(Locale.ROOT)));
        supportedTypesNames.sort(String::compareTo);
        return Strings.join(supportedTypesNames, " or ");
    }

    @FunctionalInterface
    public interface BuildFactory {
        ExpressionEvaluator.Factory build(Source source, ExpressionEvaluator.Factory field);
    }

    /**
     * A map from input type to {@link ExpressionEvaluator} ctor. Usually implemented like:
     * <pre>{@code
     *     private static final Map<DataType, BuildFactory> EVALUATORS = Map.ofEntries(
     *         Map.entry(BOOLEAN, (field, source) -> field),
     *         Map.entry(KEYWORD, ToBooleanFromStringEvaluator.Factory::new),
     *         ...
     *     );
     *
     *     @Override
     *     protected Map<DataType, BuildFactory> factories() {
     *         return EVALUATORS;
     *     }
     * }</pre>
     */
    protected abstract Map<DataType, BuildFactory> factories();

    @Override
    public ExpressionEvaluator.Factory toEvaluator(ToEvaluator toEvaluator) {
        return evaluator(toEvaluator.apply(field()));
    }

    public abstract static class AbstractEvaluator implements EvalOperator.ExpressionEvaluator {

        private static final Logger logger = LogManager.getLogger(AbstractEvaluator.class);

        protected final DriverContext driverContext;
        private final Warnings warnings;

        protected AbstractEvaluator(DriverContext driverContext, Source source) {
            this.driverContext = driverContext;
            this.warnings = Warnings.createWarnings(
                driverContext.warningsMode(),
                source.source().getLineNumber(),
                source.source().getColumnNumber(),
                source.text()
            );
        }

        protected abstract ExpressionEvaluator next();

        /**
         * Called when evaluating a {@link Block} that contains null values.
         * @return the returned Block has its own reference and the caller is responsible for releasing it.
         */
        protected abstract Block evalBlock(Block b);

        /**
         * Called when evaluating a {@link Block} that does not contain null values.
         * @return the returned Block has its own reference and the caller is responsible for releasing it.
         */
        protected abstract Block evalVector(Vector v);

        @Override
        public final Block eval(Page page) {
            try (Block block = next().eval(page)) {
                Vector vector = block.asVector();
                return vector == null ? evalBlock(block) : evalVector(vector);
            }
        }

        protected final void registerException(Exception exception) {
            logger.trace("conversion failure", exception);
            warnings.registerException(exception);
        }
    }
}
