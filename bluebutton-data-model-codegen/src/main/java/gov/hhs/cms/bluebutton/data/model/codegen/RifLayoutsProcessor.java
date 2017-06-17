package gov.hhs.cms.bluebutton.data.model.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import gov.hhs.cms.bluebutton.data.model.codegen.RifLayout.RifColumnType;
import gov.hhs.cms.bluebutton.data.model.codegen.RifLayout.RifField;
import gov.hhs.cms.bluebutton.data.model.codegen.annotations.RifLayoutsGenerator;

/**
 * This <code>javac</code> annotation {@link Processor} reads in an Excel file
 * that details a RIF field layout, and then generates the Java code required to
 * work with that layout.
 */
@AutoService(Processor.class)
public final class RifLayoutsProcessor extends AbstractProcessor {
	/**
	 * Both Maven and Eclipse hide compiler messages, so setting this constant
	 * to <code>true</code> will also log messages out to a new source file.
	 */
	private static final boolean DEBUG = true;

	private final List<String> logMessages = new LinkedList<>();

	/**
	 * @see javax.annotation.processing.AbstractProcessor#getSupportedAnnotationTypes()
	 */
	@Override
	public Set<String> getSupportedAnnotationTypes() {
		return ImmutableSet.of(RifLayoutsGenerator.class.getName());
	}

	/**
	 * @see javax.annotation.processing.AbstractProcessor#getSupportedSourceVersion()
	 */
	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	/**
	 * @see javax.annotation.processing.AbstractProcessor#process(java.util.Set,
	 *      javax.annotation.processing.RoundEnvironment)
	 */
	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		try {
			logNote("Processing triggered for '%s' on root elements '%s'.", annotations, roundEnv.getRootElements());

			Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(RifLayoutsGenerator.class);
			for (Element annotatedElement : annotatedElements) {
				if (annotatedElement.getKind() != ElementKind.PACKAGE)
					throw new RifLayoutProcessingException(annotatedElement,
							"The %s annotation is only valid on packages (i.e. in package-info.java).",
							RifLayoutsGenerator.class.getName());
				process((PackageElement) annotatedElement);
			}
		} catch (RifLayoutProcessingException e) {
			log(Diagnostic.Kind.ERROR, e.getMessage(), e.getElement());
		} catch (Exception e) {
			/*
			 * Don't allow exceptions of any type to propagate to the compiler.
			 * Log a warning and return, instead.
			 */
			StringWriter writer = new StringWriter();
			e.printStackTrace(new PrintWriter(writer));
			log(Diagnostic.Kind.ERROR, "FATAL ERROR: " + writer.toString());
		}

		if (roundEnv.processingOver())
			writeDebugLogMessages();

		return true;
	}

	/**
	 * @param annotatedPackage
	 *            the {@link PackageElement} to process that has been annotated
	 *            with {@link RifLayoutsGenerator}
	 * @throws IOException
	 *             An {@link IOException} may be thrown if errors are
	 *             encountered trying to generate source files.
	 */
	private void process(PackageElement annotatedPackage) throws IOException {
		RifLayoutsGenerator annotation = annotatedPackage.getAnnotation(RifLayoutsGenerator.class);
		logNote(annotatedPackage, "Processing package annotated with: '%s'.", annotation);

		/*
		 * Find the spreadsheet referenced by the annotation. It will define the
		 * RIF layouts.
		 */
		FileObject spreadsheetResource;
		try {
			spreadsheetResource = processingEnv.getFiler().getResource(StandardLocation.SOURCE_PATH,
					annotatedPackage.getQualifiedName().toString(), annotation.spreadsheetResource());
		} catch (IOException | IllegalArgumentException e) {
			throw new RifLayoutProcessingException(annotatedPackage,
					"Unable to find or open specified spreadsheet: '%s'.", annotation.spreadsheetResource());
		}
		logNote(annotatedPackage, "Found spreadsheet: '%s'.", annotation.spreadsheetResource());

		/*
		 * Parse the spreadsheet, extracting the layouts from it. Also: define
		 * the layouts that we expect to parse and generate code for.
		 */
		List<MappingSpec> mappingSpecs = new LinkedList<>();
		Workbook spreadsheetWorkbook = null;
		try {
			spreadsheetWorkbook = new XSSFWorkbook(spreadsheetResource.openInputStream());

			mappingSpecs.add(new MappingSpec(annotatedPackage.getQualifiedName().toString())
					.setRifLayout(RifLayout.parse(spreadsheetWorkbook, annotation.beneficiarySheet()))
					.setHeaderEntity("Beneficiary").setHeaderTable("Beneficiaries")
					.setHeaderEntityIdField("beneficiaryId").setHasLines(false));
			mappingSpecs.add(new MappingSpec(annotatedPackage.getQualifiedName().toString())
					.setRifLayout(RifLayout.parse(spreadsheetWorkbook, annotation.carrierSheet()))
					.setHeaderEntity("CarrierClaim").setHeaderTable("CarrierClaims").setHeaderEntityIdField("claimId")
					.setHasLines(true).setLineTable("CarrierClaimLines"));
		} finally {
			if (spreadsheetWorkbook != null)
				spreadsheetWorkbook.close();
		}
		logNote(annotatedPackage, "Generated mapping specification: '%s'.", mappingSpecs);

		/* Generate the code for each layout. */
		for (MappingSpec mappingSpec : mappingSpecs)
			generateCode(mappingSpec);
	}

	/**
	 * Generates the code for the specified {@link RifLayout}.
	 * 
	 * @param mappingSpec
	 *            the {@link MappingSpec} to generate code for
	 * @throws IOException
	 *             An {@link IOException} may be thrown if errors are
	 *             encountered trying to generate source files.
	 */
	private void generateCode(MappingSpec mappingSpec) throws IOException {
		/*
		 * First, create the Java enum for the RIF columns.
		 */
		generateColumnEnum(mappingSpec);

		/*
		 * Then, create the JPA Entity for the "line" fields, containing: fields
		 * and accessors.
		 */
		if (mappingSpec.getHasLines()) {
			generateLineEntity(mappingSpec);
		}

		/*
		 * Then, create the JPA Entity for the "grouped" fields, containing:
		 * fields, accessors, and a RIF-to-JPA-Entity parser.
		 */
		generateHeaderEntity(mappingSpec);
	}

	/**
	 * Generates a Java {@link Enum} with entries for each {@link RifField} in
	 * the specified {@link MappingSpec}.
	 * 
	 * @param mappingSpec
	 *            the {@link MappingSpec} of the layout to generate code for
	 * @return the Java {@link Enum} that was generated
	 * @throws IOException
	 *             An {@link IOException} may be thrown if errors are
	 *             encountered trying to generate source files.
	 */
	private TypeSpec generateColumnEnum(MappingSpec mappingSpec) throws IOException {
		TypeSpec.Builder columnEnum = TypeSpec.enumBuilder(mappingSpec.getColumnEnum()).addModifiers(Modifier.PUBLIC);
		for (int fieldIndex = 0; fieldIndex < mappingSpec.getRifLayout().getRifFields().size(); fieldIndex++) {
			RifField rifField = mappingSpec.getRifLayout().getRifFields().get(fieldIndex);
			columnEnum.addEnumConstant(rifField.getRifColumnName());
		}

		TypeSpec columnEnumFinal = columnEnum.build();
		JavaFile columnsEnumFile = JavaFile.builder(mappingSpec.getPackageName(), columnEnumFinal).build();
		columnsEnumFile.writeTo(processingEnv.getFiler());

		return columnEnumFinal;
	}

	/**
	 * Generates a Java {@link Entity} for the line {@link RifField}s in the
	 * specified {@link MappingSpec}.
	 * 
	 * @param mappingSpec
	 *            the {@link MappingSpec} of the layout to generate code for
	 * @return the Java {@link Entity} that was generated
	 * @throws IOException
	 *             An {@link IOException} may be thrown if errors are
	 *             encountered trying to generate source files.
	 */
	private TypeSpec generateLineEntity(MappingSpec mappingSpec) throws IOException {
		RifLayout rifLayout = mappingSpec.getRifLayout();

		// Create the Entity class.
		AnnotationSpec entityAnnotation = AnnotationSpec.builder(Entity.class).build();
		AnnotationSpec tableAnnotation = AnnotationSpec.builder(Table.class)
				.addMember("name", "$S", "`" + mappingSpec.getLineTable() + "`").build();
		TypeSpec.Builder lineEntity = TypeSpec.classBuilder(mappingSpec.getLineEntity()).addAnnotation(entityAnnotation)
				.addAnnotation(AnnotationSpec.builder(IdClass.class)
						.addMember("value", "$T.class", mappingSpec.getLineEntityIdClass()).build())
				.addAnnotation(tableAnnotation).addModifiers(Modifier.PUBLIC);

		// Create the @IdClass needed for the composite primary key.
		TypeSpec.Builder lineIdClass = TypeSpec.classBuilder(mappingSpec.getLineEntityIdClass())
				.addSuperinterface(Serializable.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC);
		lineIdClass.addField(
				FieldSpec.builder(long.class, "serialVersionUID", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
						.initializer("$L", 1L).build());

		// Add a field to that @IdClass for the parent claim's ID.
		RifField parentClaimRifField = rifLayout.getRifFields().stream()
				.filter(f -> mappingSpec.getHeaderEntityIdField().equals(f.getJavaFieldName())).findAny().get();
		TypeName parentClaimIdFieldType = selectJavaFieldType(parentClaimRifField);
		FieldSpec.Builder parentIdField = FieldSpec.builder(parentClaimIdFieldType,
				mappingSpec.getLineEntityParentField(), Modifier.PRIVATE);
		lineIdClass.addField(parentIdField.build());
		MethodSpec.Builder parentGetter = MethodSpec.methodBuilder("getParentClaim")
				.addStatement("return $N", mappingSpec.getLineEntityParentField()).returns(parentClaimIdFieldType);
		lineIdClass.addMethod(parentGetter.build());

		// Add a field to that @IdClass class for the line number.
		RifField rifLineNumberField = rifLayout.getRifFields().stream()
				.filter(f -> f.getJavaFieldName().equals(mappingSpec.getLineEntityLineNumberField())).findFirst().get();
		TypeName lineNumberFieldType = selectJavaFieldType(rifLineNumberField);
		FieldSpec.Builder lineNumberIdField = FieldSpec.builder(lineNumberFieldType,
				mappingSpec.getLineEntityLineNumberField(), Modifier.PRIVATE);
		lineIdClass.addField(lineNumberIdField.build());
		MethodSpec.Builder lineNumberGetter = MethodSpec
				.methodBuilder("get" + capitalize(mappingSpec.getLineEntityLineNumberField()))
				.addStatement("return $N", mappingSpec.getLineEntityLineNumberField()).returns(lineNumberFieldType);
		lineIdClass.addMethod(lineNumberGetter.build());

		// Add hashCode() and equals(...) to that @IdClass.
		lineIdClass.addMethod(generateHashCodeMethod(parentIdField.build(), lineNumberIdField.build()));
		lineIdClass.addMethod(
				generateEqualsMethod(mappingSpec.getLineEntity(), parentIdField.build(), lineNumberIdField.build()));

		// Finalize the @IdClass and nest it inside the Entity class.
		lineEntity.addType(lineIdClass.build());

		// Add a field and accessor to the "line" Entity for the parent.
		FieldSpec parentClaimField = FieldSpec
				.builder(mappingSpec.getHeaderEntity(), mappingSpec.getLineEntityParentField(), Modifier.PRIVATE)
				.addAnnotation(Id.class).addAnnotation(AnnotationSpec.builder(ManyToOne.class).build())
				.addAnnotation(AnnotationSpec.builder(JoinColumn.class)
						.addMember("name", "$S", "`" + mappingSpec.getHeaderEntityIdField() + "`").build())
				.build();
		lineEntity.addField(parentClaimField);
		MethodSpec parentClaimGetter = MethodSpec.methodBuilder(calculateGetterName(parentClaimField))
				.addModifiers(Modifier.PUBLIC).addStatement("return $N", mappingSpec.getLineEntityParentField())
				.returns(mappingSpec.getHeaderEntity()).build();
		lineEntity.addMethod(parentClaimGetter);
		MethodSpec.Builder parentClaimSetter = MethodSpec.methodBuilder(calculateSetterName(parentClaimField))
				.addModifiers(Modifier.PUBLIC).returns(void.class)
				.addParameter(mappingSpec.getHeaderEntity(), parentClaimField.name);
		addSetterStatement(false, parentClaimField, parentClaimSetter);
		lineEntity.addMethod(parentClaimSetter.build());

		// For each "line" RIF field, create an Entity field with accessors.
		for (int fieldIndex = mappingSpec.calculateFirstLineFieldIndex(); fieldIndex < rifLayout.getRifFields()
				.size(); fieldIndex++) {
			RifField rifField = rifLayout.getRifFields().get(fieldIndex);

			FieldSpec lineField = FieldSpec
					.builder(selectJavaFieldType(rifField), rifField.getJavaFieldName(), Modifier.PRIVATE)
					.addAnnotations(createAnnotations(mappingSpec, rifField)).build();
			lineEntity.addField(lineField);

			MethodSpec.Builder lineFieldGetter = MethodSpec.methodBuilder(calculateGetterName(lineField))
					.addModifiers(Modifier.PUBLIC).returns(selectJavaPropertyType(rifField));
			addGetterStatement(rifField, lineField, lineFieldGetter);
			lineEntity.addMethod(lineFieldGetter.build());

			MethodSpec.Builder lineFieldSetter = MethodSpec.methodBuilder(calculateSetterName(lineField))
					.addModifiers(Modifier.PUBLIC).returns(void.class)
					.addParameter(selectJavaPropertyType(rifField), lineField.name);
			addSetterStatement(rifField, lineField, lineFieldSetter);
			lineEntity.addMethod(lineFieldSetter.build());
		}

		TypeSpec lineEntityFinal = lineEntity.build();
		JavaFile lineEntityClassFile = JavaFile.builder(mappingSpec.getPackageName(), lineEntityFinal).build();
		lineEntityClassFile.writeTo(processingEnv.getFiler());

		return lineEntityFinal;
	}

	/**
	 * Generates a Java {@link Entity} for the header {@link RifField}s in the
	 * specified {@link MappingSpec}.
	 * 
	 * @param mappingSpec
	 *            the {@link MappingSpec} of the layout to generate code for
	 * @return the Java {@link Entity} that was generated
	 * @throws IOException
	 *             An {@link IOException} may be thrown if errors are
	 *             encountered trying to generate source files.
	 */
	private TypeSpec generateHeaderEntity(MappingSpec mappingSpec) throws IOException {
		// Create the Entity class.
		AnnotationSpec entityAnnotation = AnnotationSpec.builder(Entity.class).build();
		AnnotationSpec tableAnnotation = AnnotationSpec.builder(Table.class)
				.addMember("name", "$S", "`" + mappingSpec.getHeaderTable() + "`").build();
		TypeSpec.Builder headerEntityClass = TypeSpec.classBuilder(mappingSpec.getHeaderEntity())
				.addAnnotation(entityAnnotation).addAnnotation(tableAnnotation).addModifiers(Modifier.PUBLIC);

		// Create an Entity field with accessors for each RIF field.
		for (int fieldIndex = 0; fieldIndex <= mappingSpec.calculateLastHeaderFieldIndex(); fieldIndex++) {
			RifField rifField = mappingSpec.getRifLayout().getRifFields().get(fieldIndex);

			FieldSpec headerField = FieldSpec
					.builder(selectJavaFieldType(rifField), rifField.getJavaFieldName(), Modifier.PRIVATE)
					.addAnnotations(createAnnotations(mappingSpec, rifField)).build();
			headerEntityClass.addField(headerField);

			MethodSpec.Builder headerFieldGetter = MethodSpec.methodBuilder(calculateGetterName(headerField))
					.addModifiers(Modifier.PUBLIC).returns(selectJavaPropertyType(rifField));
			addGetterStatement(rifField, headerField, headerFieldGetter);
			headerEntityClass.addMethod(headerFieldGetter.build());

			MethodSpec.Builder headerFieldSetter = MethodSpec.methodBuilder(calculateSetterName(headerField))
					.addModifiers(Modifier.PUBLIC).returns(void.class)
					.addParameter(selectJavaPropertyType(rifField), headerField.name);
			addSetterStatement(rifField, headerField, headerFieldSetter);
			headerEntityClass.addMethod(headerFieldSetter.build());
		}

		// Add the parent-to-child join field and accessor, if appropriate.
		if (mappingSpec.getHasLines()) {
			ParameterizedTypeName childFieldType = ParameterizedTypeName.get(ClassName.get(List.class),
					mappingSpec.getLineEntity());

			FieldSpec.Builder childField = FieldSpec.builder(childFieldType, "lines", Modifier.PRIVATE)
					.initializer("new $T<>()", LinkedList.class);
			childField.addAnnotation(AnnotationSpec.builder(OneToMany.class)
					.addMember("mappedBy", "$S", mappingSpec.getLineEntityParentField())
					.addMember("orphanRemoval", "$L", true).addMember("fetch", "$T.EAGER", FetchType.class)
					.addMember("cascade", "$T.ALL", CascadeType.class).build());
			childField.addAnnotation(AnnotationSpec.builder(OrderBy.class)
					.addMember("value", "$S", mappingSpec.getLineEntityLineNumberField() + " ASC").build());
			headerEntityClass.addField(childField.build());

			MethodSpec childGetter = MethodSpec.methodBuilder("getLines").addModifiers(Modifier.PUBLIC)
					.addStatement("return $N", "lines").returns(childFieldType).build();
			headerEntityClass.addMethod(childGetter);
		}

		TypeSpec headerEntityFinal = headerEntityClass.build();
		JavaFile headerEntityFile = JavaFile.builder(mappingSpec.getPackageName(), headerEntityFinal).build();
		headerEntityFile.writeTo(processingEnv.getFiler());

		return headerEntityFinal;
	}

	/**
	 * @param fields
	 *            the fields that should be hashed
	 * @return a new <code>hashCode()</code> implementation that uses the
	 *         specified fields
	 */
	private static MethodSpec generateHashCodeMethod(FieldSpec... fields) {
		MethodSpec.Builder hashCodeMethod = MethodSpec.methodBuilder("hashCode").addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC).returns(int.class).addStatement("return $T.hash($L)", Objects.class,
						Arrays.stream(fields).map(f -> f.name).collect(Collectors.joining(", ")));
		return hashCodeMethod.build();
	}

	/**
	 * @param typeName
	 *            the {@link TypeName} of the class to add this method for
	 * @param fields
	 *            the fields that should be compared
	 * @return a new <code>equals(...)</code> implementation that uses the
	 *         specified fields
	 */
	private static MethodSpec generateEqualsMethod(TypeName typeName, FieldSpec... fields) {
		MethodSpec.Builder hashCodeMethod = MethodSpec.methodBuilder("equals").addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC).addParameter(Object.class, "obj").returns(boolean.class);

		hashCodeMethod.beginControlFlow("if (this == obj)").addStatement("return true").endControlFlow();
		hashCodeMethod.beginControlFlow("if (obj == null)").addStatement("return false").endControlFlow();
		hashCodeMethod.beginControlFlow("if (getClass() != obj.getClass())").addStatement("return false")
				.endControlFlow();
		hashCodeMethod.addStatement("$T other = ($T) obj", typeName, typeName);
		for (FieldSpec field : fields) {
			hashCodeMethod.beginControlFlow("if ($T.deepEquals($N, other.$N))", Objects.class, field, field)
					.addStatement("return false").endControlFlow();
		}
		hashCodeMethod.addStatement("return true");

		return hashCodeMethod.build();
	}

	/**
	 * @param rifField
	 *            the {@link RifField} to select the corresponding Java type for
	 * @return the {@link TypeName} of the Java type that should be used to
	 *         represent the specified {@link RifField} in a JPA entity
	 */
	private static TypeName selectJavaFieldType(RifField rifField) {
		if (rifField.getRifColumnType() == RifColumnType.CHAR && rifField.getRifColumnLength() == 1
				&& !rifField.isRifColumnOptional())
			return TypeName.CHAR;
		else if (rifField.getRifColumnType() == RifColumnType.CHAR && rifField.getRifColumnLength() == 1
				&& rifField.isRifColumnOptional())
			return ClassName.get(Character.class);
		else if (rifField.getRifColumnType() == RifColumnType.CHAR)
			return ClassName.get(String.class);
		else if (rifField.getRifColumnType() == RifColumnType.DATE && rifField.getRifColumnLength() == 8)
			return ClassName.get(LocalDate.class);
		else if (rifField.getRifColumnType() == RifColumnType.NUM)
			return ClassName.get(BigDecimal.class);
		else
			throw new IllegalArgumentException("Unhandled field type: " + rifField);
	}

	/**
	 * @param rifField
	 *            the {@link RifField} to select the corresponding Java
	 *            getter/setter type for
	 * @return the {@link TypeName} of the Java type that should be used to
	 *         represent the specified {@link RifField}'s getter/setter in a JPA
	 *         entity
	 */
	private static TypeName selectJavaPropertyType(RifField rifField) {
		if (!rifField.isRifColumnOptional())
			return selectJavaFieldType(rifField);
		else
			return ParameterizedTypeName.get(ClassName.get(Optional.class), selectJavaFieldType(rifField));
	}

	/**
	 * @param mappingSpec
	 *            the {@link MappingSpec} for the specified {@link RifField}
	 * @param rifField
	 *            the {@link RifField} to create the corresponding
	 *            {@link AnnotationSpec}s for
	 * @return an ordered {@link List} of {@link AnnotationSpec}s representing
	 *         the JPA, etc. annotations that should be applied to the specified
	 *         {@link RifField}
	 */
	private static List<AnnotationSpec> createAnnotations(MappingSpec mappingSpec, RifField rifField) {
		LinkedList<AnnotationSpec> annotations = new LinkedList<>();

		// Add an @Id annotation, if appropriate.
		if (rifField.getJavaFieldName().equals(mappingSpec.getHeaderEntityIdField()) || (mappingSpec.getHasLines()
				&& rifField.getJavaFieldName().equals(mappingSpec.getLineEntityLineNumberField()))) {
			AnnotationSpec.Builder idAnnotation = AnnotationSpec.builder(Id.class);
			annotations.add(idAnnotation.build());
		}

		// Add an @Column annotation to every column.
		AnnotationSpec.Builder columnAnnotation = AnnotationSpec.builder(Column.class)
				.addMember("name", "$S", "`" + rifField.getJavaFieldName() + "`")
				.addMember("nullable", "$L", rifField.isRifColumnOptional());
		if (rifField.getRifColumnType() == RifColumnType.CHAR) {
			columnAnnotation.addMember("length", "$L", rifField.getRifColumnLength());
		} else if (rifField.getRifColumnType() == RifColumnType.NUM) {
			/*
			 * In SQL, the precision is the number of digits in the unscaled
			 * value, e.g. "123.45" has a precision of 5. The scale is the
			 * number of digits to the right of the decimal point, e.g. "123.45"
			 * has a scale of 2.
			 */
			columnAnnotation.addMember("precision", "$L", rifField.getRifColumnLength());
			columnAnnotation.addMember("scale", "$L", rifField.getRifColumnScale().get());
		}
		annotations.add(columnAnnotation.build());

		return annotations;
	}

	/**
	 * @param entityField
	 *            the JPA entity {@link FieldSpec} for the field that the
	 *            desired getter will wrap
	 * @return the name of the Java "getter" for the specified {@link FieldSpec}
	 */
	private static String calculateGetterName(FieldSpec entityField) {
		if (entityField.type.equals(TypeName.BOOLEAN) || entityField.type.equals(ClassName.get(Boolean.class)))
			return "is" + capitalize(entityField.name);
		else
			return "get" + capitalize(entityField.name);
	}

	/**
	 * @param rifField
	 *            the {@link RifField} to generate the "getter" statement for
	 * @param entityField
	 *            the {@link FieldSpec} for the field being wrapped by the
	 *            "getter"
	 * @param entityGetter
	 *            the "getter" method to generate the statement in
	 */
	private static void addGetterStatement(RifField rifField, FieldSpec entityField, MethodSpec.Builder entityGetter) {
		if (!rifField.isRifColumnOptional())
			entityGetter.addStatement("return $N", entityField);
		else
			entityGetter.addStatement("return $T.ofNullable($N)", Optional.class, entityField);
	}

	/**
	 * @param entityField
	 *            the JPA entity {@link FieldSpec} for the field that the
	 *            desired setter will wrap
	 * @return the name of the Java "setter" for the specified {@link FieldSpec}
	 */
	private static String calculateSetterName(FieldSpec entityField) {
		return "set" + capitalize(entityField.name);
	}

	/**
	 * @param rifField
	 *            the {@link RifField} to generate the "setter" statement for
	 * @param entityField
	 *            the {@link FieldSpec} for the field being wrapped by the
	 *            "setter"
	 * @param entitySetter
	 *            the "setter" method to generate the statement in
	 */
	private static void addSetterStatement(RifField rifField, FieldSpec entityField, MethodSpec.Builder entitySetter) {
		addSetterStatement(rifField.isRifColumnOptional(), entityField, entitySetter);
	}

	/**
	 * @param rifField
	 *            <code>true</code> if the property is an {@link Optional} one,
	 *            <code>false</code> otherwise
	 * @param entityField
	 *            the {@link FieldSpec} for the field being wrapped by the
	 *            "setter"
	 * @param entitySetter
	 *            the "setter" method to generate the statement in
	 */
	private static void addSetterStatement(boolean optional, FieldSpec entityField, MethodSpec.Builder entitySetter) {
		if (!optional)
			entitySetter.addStatement("this.$N = $N", entityField, entityField);
		else
			entitySetter.addStatement("this.$N = $N.orElse(null)", entityField, entityField);
	}

	/**
	 * @param name
	 *            the {@link String} to capitalize the first letter of
	 * @return a capitalized {@link String}
	 */
	private static String capitalize(String name) {
		char first = name.charAt(0);
		return String.format("%s%s", Character.toUpperCase(first), name.substring(1));
	}

	/**
	 * Reports the specified log message.
	 * 
	 * @param logEntryKind
	 *            the {@link Diagnostic.Kind} of log entry to add
	 * @param associatedElement
	 *            the Java AST {@link Element} that the log entry should be
	 *            associated with, or <code>null</code>
	 * @param messageFormat
	 *            the log message format {@link String}
	 * @param messageArguments
	 *            the log message format arguments
	 */
	private void log(Diagnostic.Kind logEntryKind, Element associatedElement, String messageFormat,
			Object... messageArguments) {
		String logMessage = String.format(messageFormat, messageArguments);
		processingEnv.getMessager().printMessage(logEntryKind, logMessage, associatedElement);

		String logMessageFull;
		if (associatedElement != null)
			logMessageFull = String.format("[%s] at '%s': %s", logEntryKind, associatedElement, logMessage);
		else
			logMessageFull = String.format("[%s]: %s", logEntryKind, logMessage);
		logMessages.add(logMessageFull);
	}

	/**
	 * Reports the specified log message.
	 * 
	 * @param logEntryKind
	 *            the {@link Diagnostic.Kind} of log entry to add
	 * @param messageFormat
	 *            the log message format {@link String}
	 * @param messageArguments
	 *            the log message format arguments
	 */
	private void log(Diagnostic.Kind logEntryKind, String messageFormat, Object... messageArguments) {
		log(logEntryKind, null, messageFormat, messageArguments);
	}

	/**
	 * Reports the specified log message.
	 * 
	 * @param associatedElement
	 *            the Java AST {@link Element} that the log entry should be
	 *            associated with, or <code>null</code>
	 * @param messageFormat
	 *            the log message format {@link String}
	 * @param messageArguments
	 *            the log message format arguments
	 */
	private void logNote(Element associatedElement, String messageFormat, Object... messageArguments) {
		log(Diagnostic.Kind.NOTE, associatedElement, messageFormat, messageArguments);
	}

	/**
	 * Reports the specified log message.
	 * 
	 * @param associatedElement
	 *            the Java AST {@link Element} that the log entry should be
	 *            associated with, or <code>null</code>
	 * @param messageFormat
	 *            the log message format {@link String}
	 * @param messageArguments
	 *            the log message format arguments
	 */
	private void logNote(String messageFormat, Object... messageArguments) {
		log(Diagnostic.Kind.NOTE, null, messageFormat, messageArguments);
	}

	/**
	 * Writes out all of the messages in {@link #logMessages} to a log file in
	 * the annotation-generated source directory.
	 */
	private void writeDebugLogMessages() {
		if (!DEBUG)
			return;

		try {
			FileObject logResource = processingEnv.getFiler().createResource(StandardLocation.SOURCE_OUTPUT, "",
					"rif-layout-processor-log.txt");
			Writer logWriter = logResource.openWriter();
			for (String logMessage : logMessages) {
				logWriter.write(logMessage);
				logWriter.write('\n');
			}
			logWriter.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
