/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.appsearch.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Generates java code for a translator from a {@link androidx.appsearch.app.GenericDocument} to
 * a data class.
 */
class FromGenericDocumentCodeGenerator {
    private final ProcessingEnvironment mEnv;
    private final IntrospectionHelper mHelper;
    private final AppSearchDocumentModel mModel;

    public static void generate(
            @NonNull ProcessingEnvironment env,
            @NonNull AppSearchDocumentModel model,
            @NonNull TypeSpec.Builder classBuilder) throws ProcessingException {
        new FromGenericDocumentCodeGenerator(env, model).generate(classBuilder);
    }

    private FromGenericDocumentCodeGenerator(
            @NonNull ProcessingEnvironment env, @NonNull AppSearchDocumentModel model) {
        mEnv = env;
        mHelper = new IntrospectionHelper(env);
        mModel = model;
    }

    private void generate(TypeSpec.Builder classBuilder) throws ProcessingException {
        classBuilder.addMethod(createFromGenericDocumentMethod());
    }

    private MethodSpec createFromGenericDocumentMethod() throws ProcessingException {
        // Method header
        TypeName classType = TypeName.get(mModel.getClassElement().asType());
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("fromGenericDocument")
                .addModifiers(Modifier.PUBLIC)
                .returns(classType)
                .addAnnotation(Override.class)
                .addParameter(mHelper.getAppSearchClass("GenericDocument"), "genericDoc");

        unpackSpecialFields(methodBuilder);

        // Unpack properties from the GenericDocument into the format desired by the data class
        for (Map.Entry<String, VariableElement> entry : mModel.getPropertyFields().entrySet()) {
            fieldFromGenericDoc(methodBuilder, entry.getKey(), entry.getValue());
        }

        // Create an instance of the data class via the chosen constructor
        methodBuilder.addStatement(
                "$T dataClass = new $T($L)", classType, classType, getConstructorParams());

        // Assign all fields which weren't set in the constructor
        for (String field : mModel.getAllFields().keySet()) {
            CodeBlock fieldWrite = createAppSearchFieldWrite(field);
            if (fieldWrite != null) {
                methodBuilder.addStatement(fieldWrite);
            }
        }

        methodBuilder.addStatement("return dataClass");
        return methodBuilder.build();
    }

    /**
     * Converts a field from a {@link androidx.appsearch.app.GenericDocument} into a format suitable
     * for the data class.
     */
    private void fieldFromGenericDoc(
            @NonNull MethodSpec.Builder builder,
            @NonNull String fieldName,
            @NonNull VariableElement property) throws ProcessingException {
        // Scenario 1: field is assignable from List
        //   1a: ListForLoopAssign
        //       List contains boxed Long, Integer, Double, Float, Boolean or byte[]. We have to
        //       unpack it from a primitive array of type long[], double[], boolean[], or byte[][]
        //       by reading each element one-by-one and assigning it. The compiler takes care of
        //       unboxing.
        //
        //   1b: ListCallArraysAsList
        //       List contains String or GenericDocument.
        //       We have to convert this from an array of String[] or GenericDocument[], but no
        //       conversion of the collection elements is needed. We can use Arrays#asList for this.
        //
        //   1c: ListForLoopCallFromGenericDocument
        //       List contains a class which is annotated with @AppSearchDocument.
        //       We have to convert this from an array of GenericDocument[], by reading each element
        //       one-by-one and converting it through the standard conversion machinery.
        //
        //   1x: List contains any other kind of class. This unsupported and compilation fails.
        //       Note: List<Byte[]>, List<Byte>, List<List<Byte>>, Set<String> are in this category.
        //       We don't support such conversions currently, but in principle they are possible and
        //       could be implemented.

        // Scenario 2: field is an Array
        //   2a: ArrayForLoopAssign
        //       Array is of type Long[], Integer[], int[], Double[], Float[], float[], Boolean[],
        //       or Byte[].
        //       We have to unpack it from a primitive array of type long[], double[], boolean[] or
        //       byte[] by reading each element one-by-one and assigning it. The compiler takes care
        //       of unboxing.
        //
        //   2b: ArrayUseDirectly
        //       Array is of type String[], long[], double[], boolean[], byte[][] or
        //       GenericDocument[].
        //       We can directly use this field with no conversion.
        //
        //   2c: ArrayForLoopCallFromGenericDocument
        //       Array is of a class which is annotated with @AppSearchDocument.
        //       We have to convert this from an array of GenericDocument[], by reading each element
        //       one-by-one and converting it through the standard conversion machinery.
        //
        //   2d: Array is of class byte[]. This is actually a single-valued field as byte arrays are
        //       natively supported by Icing, and is handled as Scenario 3a.
        //
        //   2x: Array is of any other kind of class. This unsupported and compilation fails.
        //       Note: Byte[][] is in this category. We don't support such conversions
        //       currently, but in principle they are possible and could be implemented.

        // Scenario 3: Single valued fields
        //   3a: FieldUseDirectlyWithNullCheck
        //       Field is of type String, Long, Integer, Double, Float, Boolean, byte[] or
        //       GenericDocument.
        //       We can use this field directly, after testing for null. The java compiler will box
        //       or unbox as needed.
        //
        //   3b: FieldUseDirectlyWithoutNullCheck
        //       Field is of type long, int, double, float, or boolean.
        //       We can use this field directly. Since we cannot assign null, we must assign the
        //       default value if the field is not specified. The java compiler will box or unbox as
        //       needed
        //
        //   3c: FieldCallToGenericDocument
        //       Field is of a class which is annotated with @AppSearchDocument.
        //       We have to convert this from a GenericDocument through the standard conversion
        //       machinery.
        //
        //   3x: Field is of any other kind of class. This is unsupported and compilation fails.

        String propertyName = mModel.getPropertyName(property);
        if (tryConvertToList(builder, fieldName, propertyName, property)) {
            return;
        }
        if (tryConvertToArray(builder, fieldName, propertyName, property)) {
            return;
        }
        convertToField(builder, fieldName, propertyName, property);
    }

    /**
     * If the given field is a List, generates code to read it from a repeated GenericDocument
     * property and returns true. If the field is not a List, returns false.
     */
    private boolean tryConvertToList(
            @NonNull MethodSpec.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull VariableElement property) throws ProcessingException {
        Types typeUtil = mEnv.getTypeUtils();
        if (!typeUtil.isAssignable(mHelper.mListType, typeUtil.erasure(property.asType()))) {
            return false;  // This is not a scenario 1 list
        }

        List<? extends TypeMirror> genericTypes =
                ((DeclaredType) property.asType()).getTypeArguments();
        TypeMirror propertyType = genericTypes.get(0);

        // TODO(b/156296904): Handle scenario 1c (ListForLoopCallFromGenericDocument)
        CodeBlock.Builder builder = CodeBlock.builder();
        if (!tryListForLoopAssign(builder, fieldName, propertyName, propertyType)  // 1a
                && !tryListCallArraysAsList(
                        builder, fieldName, propertyName, propertyType)) {  // 1b
            // Scenario 1x
            throw new ProcessingException(
                    "Unhandled in property type (1x): " + property.asType().toString(), property);
        }

        method.addCode(builder.build());
        return true;
    }

    //   1a: ListForLoopAssign
    //       List contains boxed Long, Integer, Double, Float, Boolean or byte[]. We have to
    //       unpack it from a primitive array of type long[], double[], boolean[], or byte[][]
    //       by reading each element one-by-one and assigning it. The compiler takes care of
    //       unboxing.
    private boolean tryListForLoopAssign(
            @NonNull CodeBlock.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull TypeMirror propertyType) {
        Types typeUtil = mEnv.getTypeUtils();
        CodeBlock.Builder body = CodeBlock.builder();

        // Copy the property to refer to it more easily.
        if (typeUtil.isSameType(propertyType, mHelper.mLongBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mIntegerBoxType)) {
            body.addStatement(
                    "long[] $NCopy = genericDoc.getPropertyLongArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mDoubleBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mFloatBoxType)) {
            body.addStatement(
                    "double[] $NCopy = genericDoc.getPropertyDoubleArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mBooleanBoxType)) {
            body.addStatement(
                    "boolean[] $NCopy = genericDoc.getPropertyBooleanArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mByteArrayType)) {
            body.addStatement(
                    "byte[][] $NCopy = genericDoc.getPropertyBytesArray($S)",
                    fieldName, propertyName);

        } else {
            // This is not a type 1a list.
            return false;
        }

        // Create the destination list
        body.addStatement(
                "$T $NConv = null",
                ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(propertyType)),
                fieldName);

        // If not null, iterate and assign
        body
                .add("if ($NCopy != null) {\n", fieldName).indent()
                .addStatement(
                        "$NConv = new $T<>($NCopy.length)", fieldName, ArrayList.class, fieldName)
                .add("for (int i = 0; i < $NCopy.length; i++) {\n", fieldName).indent()
                .addStatement("$NConv.set(i, $NCopy[i])", fieldName, fieldName)
                .unindent().add("}\n")
                .unindent().add("}\n");
        method.add(body.build());
        return true;
    }

    //   1b: ListCallArraysAsList
    //       List contains String or GenericDocument.
    //       We have to convert this from an array of String[] or GenericDocument[], but no
    //       conversion of the collection elements is needed. We can use Arrays#asList for this.
    private boolean tryListCallArraysAsList(
            @NonNull CodeBlock.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull TypeMirror propertyType) {
        Types typeUtil = mEnv.getTypeUtils();
        CodeBlock.Builder body = CodeBlock.builder();

        // TODO(b/156296904): Handle GenericDocument
        if (typeUtil.isSameType(propertyType, mHelper.mStringType)) {
            body.addStatement(
                    "String[] $NCopy = genericDoc.getPropertyStringArray($S)",
                    fieldName, propertyName);

        } else {
            // This is not a type 1b list.
            return false;
        }

        // Create the destination list
        body.addStatement(
                "$T $NConv = null",
                ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(propertyType)),
                fieldName);

        // If not null, iterate and assign
        body
                .add("if ($NCopy != null) {\n", fieldName).indent()
                .addStatement("$NConv = $T.asList($NCopy)", fieldName, Arrays.class, fieldName)
                .unindent().add("}\n");

        method.add(body.build());
        return true;
    }

    /**
     * If the given field is an array, generates code to read it from a repeated GenericDocument
     * property and returns true. If the field is not an array, returns false.
     */
    private boolean tryConvertToArray(
            @NonNull MethodSpec.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull VariableElement property) throws ProcessingException {
        Types typeUtil = mEnv.getTypeUtils();
        if (property.asType().getKind() != TypeKind.ARRAY
                // Byte arrays have a native representation in Icing, so they are not considered a
                // "repeated" type
                || typeUtil.isSameType(property.asType(), mHelper.mByteArrayType)) {
            return false;  // This is not a scenario 2 array
        }

        TypeMirror propertyType = ((ArrayType) property.asType()).getComponentType();

        // TODO(b/156296904): Handle scenario 2c (ArrayForLoopCallToGenericDocument)
        CodeBlock.Builder builder = CodeBlock.builder();
        if (!tryArrayForLoopAssign(builder, fieldName, propertyName, propertyType)  // 2a
                && !tryArrayUseDirectly(builder, fieldName, propertyName, propertyType)) {  // 2b
            // Scenario 2x
            throw new ProcessingException(
                    "Unhandled in property type (2x): " + property.asType().toString(), property);
        }

        method.addCode(builder.build());
        return true;
    }

    //   2a: ArrayForLoopAssign
    //       Array is of type Long[], Integer[], int[], Double[], Float[], float[], Boolean[],
    //       or Byte[].
    //       We have to unpack it from a primitive array of type long[], double[], boolean[] or
    //       byte[] by reading each element one-by-one and assigning it. The compiler takes care
    //       of unboxing.
    private boolean tryArrayForLoopAssign(
            @NonNull CodeBlock.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull TypeMirror propertyType) {
        Types typeUtil = mEnv.getTypeUtils();
        CodeBlock.Builder body = CodeBlock.builder();

        // Copy the property to refer to it more easily.
        if (typeUtil.isSameType(propertyType, mHelper.mLongBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mIntegerBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mIntPrimitiveType)) {
            body.addStatement(
                    "long[] $NCopy = genericDoc.getPropertyLongArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mDoubleBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mFloatBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mFloatPrimitiveType)) {
            body.addStatement(
                    "double[] $NCopy = genericDoc.getPropertyDoubleArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mBooleanBoxType)) {
            body.addStatement(
                    "boolean[] $NCopy = genericDoc.getPropertyBooleanArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mByteBoxType)) {
            body.addStatement(
                    "byte[][] $NCopy = genericDoc.getPropertyBytesArray($S)",
                    fieldName, propertyName);

        } else {
            // This is not a type 2a array.
            return false;
        }

        // Create the destination array
        body.addStatement("$T[] $NConv = null", propertyType, fieldName);

        // If not null, iterate and assign
        body
                .add("if ($NCopy != null) {\n", fieldName).indent()
                .addStatement("$NConv = new $T[$NCopy.length]", fieldName, propertyType, fieldName)
                .add("for (int i = 0; i < $NCopy.length; i++) {\n", fieldName).indent()
                .addStatement("$NConv[i] = $NCopy[i]", fieldName, fieldName)
                .unindent().add("}\n")
                .unindent().add("}\n");

        method.add(body.build());
        return true;
    }

    //   2b: ArrayUseDirectly
    //       Array is of type String[], long[], double[], boolean[], byte[][] or
    //       GenericDocument[].
    //       We can directly use this field with no conversion.
    private boolean tryArrayUseDirectly(
            @NonNull CodeBlock.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull TypeMirror propertyType) {
        Types typeUtil = mEnv.getTypeUtils();
        CodeBlock.Builder body = CodeBlock.builder();

        // Copy the field to a local variable to make it easier to refer to repeatedly
        // TODO(b/156296904): Handle GenericDocument
        if (typeUtil.isSameType(propertyType, mHelper.mStringType)) {
            body.addStatement(
                    "String[] $NConv = genericDoc.getPropertyStringArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mLongPrimitiveType)) {
            body.addStatement(
                    "long[] $NConv = genericDoc.getPropertyLongArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mDoublePrimitiveType)) {
            body.addStatement(
                    "double[] $NConv = genericDoc.getPropertyDoubleArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mBooleanPrimitiveType)) {
            body.addStatement(
                    "boolean[] $NConv = genericDoc.getPropertyBooleanArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mByteArrayType)) {
            body.addStatement(
                    "byte[][] $NConv = genericDoc.getPropertyBytesArray($S)",
                    fieldName, propertyName);

        } else {
            // This is not a type 2b array.
            return false;
        }

        method.add(body.build());
        return true;
    }

    /**
     * Given a field which is a single element (non-collection), generates code to read it from a
     * repeated GenericDocument property.
     */
    private void convertToField(
            @NonNull MethodSpec.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull VariableElement property) throws ProcessingException {
        // TODO(b/156296904): Handle scenario 3c (FieldCallToGenericDocument)
        CodeBlock.Builder builder = CodeBlock.builder();
        if (!tryFieldUseDirectlyWithNullCheck(
                builder, fieldName, propertyName, property.asType())  // 3a
                && !tryFieldUseDirectlyWithoutNullCheck(
                        builder, fieldName, propertyName, property.asType())) {  // 3b
            // Scenario 3x
            throw new ProcessingException(
                    "Unhandled in property type (3x): " + property.asType().toString(), property);
        }
        method.addCode(builder.build());
    }

    //   3a: FieldUseDirectlyWithNullCheck
    //       Field is of type String, Long, Integer, Double, Float, Boolean, byte[] or
    //       GenericDocument.
    //       We can use this field directly, after testing for null. The java compiler will box
    //       or unbox as needed.
    private boolean tryFieldUseDirectlyWithNullCheck(
            @NonNull CodeBlock.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull TypeMirror propertyType) {
        Types typeUtil = mEnv.getTypeUtils();

        // Copy the field into a local variable to make it easier to refer to it repeatedly.
        // Even though we want a single field, we can't use genericDoc.getPropertyString() (and
        // relatives) because we need to be able to check for null.
        CodeBlock.Builder body = CodeBlock.builder();
        // TODO(b/156296904): Handle GenericDocument
        if (typeUtil.isSameType(propertyType, mHelper.mStringType)) {
            body.addStatement(
                    "String[] $NCopy = genericDoc.getPropertyStringArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mLongBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mIntegerBoxType)) {
            body.addStatement(
                    "long[] $NCopy = genericDoc.getPropertyLongArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mDoubleBoxType)
                || typeUtil.isSameType(propertyType, mHelper.mFloatBoxType)) {
            body.addStatement(
                    "double[] $NCopy = genericDoc.getPropertyDoubleArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mBooleanBoxType)) {
            body.addStatement(
                    "boolean[] $NCopy = genericDoc.getPropertyBooleanArray($S)",
                    fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mByteArrayType)) {
            body.addStatement(
                    "byte[][] $NCopy = genericDoc.getPropertyBytesArray($S)",
                    fieldName, propertyName);

        } else {
            // This is not a type 3a field
            return false;
        }

        // Create the destination field
        body.addStatement("$T $NConv = null", propertyType, fieldName);

        // If not null, assign
        body
                .add("if ($NCopy != null && $NCopy.length != 0) {\n", fieldName, fieldName)
                .indent()
                .addStatement("$NConv = $NCopy[0]", fieldName, fieldName)
                .unindent().add("}\n");

        method.add(body.build());
        return true;
    }

    //   3b: FieldUseDirectlyWithoutNullCheck
    //       Field is of type long, int, double, float, or boolean.
    //       We can use this field directly. Since we cannot assign null, we must assign the
    //       default value if the field is not specified. The java compiler will box or unbox as
    //       needed
    private boolean tryFieldUseDirectlyWithoutNullCheck(
            @NonNull CodeBlock.Builder method,
            @NonNull String fieldName,
            @NonNull String propertyName,
            @NonNull TypeMirror propertyType) {
        Types typeUtil = mEnv.getTypeUtils();
        if (typeUtil.isSameType(propertyType, mHelper.mLongPrimitiveType)
                || typeUtil.isSameType(propertyType, mHelper.mIntPrimitiveType)) {
            method.addStatement(
                    "$T $NConv = genericDoc.getPropertyLong($S)",
                    propertyType, fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mDoublePrimitiveType)
                || typeUtil.isSameType(propertyType, mHelper.mFloatPrimitiveType)) {
            method.addStatement(
                    "$T $NConv = genericDoc.getPropertyDouble($S)",
                    propertyType, fieldName, propertyName);

        } else if (typeUtil.isSameType(propertyType, mHelper.mBooleanPrimitiveType)) {
            method.addStatement(
                    "$T $NConv = genericDoc.getPropertyBoolean($S)",
                    propertyType, fieldName, propertyName);

        } else {
            // This is not a type 3b field
            return false;
        }

        return true;
    }

    private CodeBlock getConstructorParams() {
        CodeBlock.Builder builder = CodeBlock.builder();
        List<String> params = mModel.getChosenConstructorParams();
        if (params.size() > 0) {
            builder.add("$NConv", params.get(0));
        }
        for (int i = 1; i < params.size(); i++) {
            builder.add(", $NConv", params.get(i));
        }
        return builder.build();
    }

    private void unpackSpecialFields(@NonNull MethodSpec.Builder method) {
        for (AppSearchDocumentModel.SpecialField specialField :
                AppSearchDocumentModel.SpecialField.values()) {
            String fieldName = mModel.getSpecialFieldName(specialField);
            if (fieldName == null) {
                continue;  // The data class doesn't have this field, so no need to unpack it.
            }
            switch (specialField) {
                case URI:
                    method.addStatement("String $NConv = genericDoc.getUri()", fieldName);
                    break;
                case NAMESPACE:
                    method.addStatement("String $NConv = genericDoc.getNamespace()", fieldName);
                    break;
                case CREATION_TIMESTAMP_MILLIS:
                    method.addStatement(
                            "long $NConv = genericDoc.getCreationTimestampMillis()", fieldName);
                    break;
                case TTL_MILLIS:
                    method.addStatement("long $NConv = genericDoc.getTtlMillis()", fieldName);
                    break;
                case SCORE:
                    method.addStatement("int $NConv = genericDoc.getScore()", fieldName);
                    break;
            }
        }
    }

    @Nullable
    private CodeBlock createAppSearchFieldWrite(@NonNull String fieldName) {
        switch (mModel.getFieldWriteKind(fieldName)) {
            case FIELD:
                return CodeBlock.of("dataClass.$N = $NConv", fieldName, fieldName);
            case SETTER:
                String setter = mModel.getAccessorName(fieldName, /*get=*/ false);
                return CodeBlock.of("dataClass.$N($NConv)", setter, fieldName);
            default:
                return null;  // Constructor params should already have been set
        }
    }
}
