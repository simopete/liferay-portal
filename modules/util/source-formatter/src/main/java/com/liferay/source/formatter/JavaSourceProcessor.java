/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.source.formatter;

import com.liferay.portal.kernel.io.unsync.UnsyncBufferedReader;
import com.liferay.portal.kernel.io.unsync.UnsyncStringReader;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Tuple;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.tools.ImportsFormatter;
import com.liferay.portal.tools.JavaImportsFormatter;
import com.liferay.portal.tools.ToolsUtil;
import com.liferay.source.formatter.util.FileUtil;

import com.thoughtworks.qdox.JavaDocBuilder;
import com.thoughtworks.qdox.model.JavaSource;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Hugo Huijser
 */
public class JavaSourceProcessor extends BaseSourceProcessor {

	@Override
	public String[] getIncludes() {
		return _INCLUDES;
	}

	protected String applyDiamondOperator(String content) {
		Matcher matcher = _diamondOperatorPattern.matcher(content);

		while (matcher.find()) {
			String match = matcher.group();
			String whitespace = matcher.group(4);
			String parameterType = matcher.group(5);

			String replacement = StringUtil.replaceFirst(
				match, whitespace + "<" + parameterType + ">", "<>");

			content = StringUtil.replace(content, match, replacement);
		}

		return content;
	}

	protected String checkAnnotationMetaTypeProperties(
		String content, String annotation) {

		if (!annotation.contains("@Meta.")) {
			return content;
		}

		Matcher matcher = _annotationMetaTypePattern.matcher(annotation);

		if (!matcher.find()) {
			return content;
		}

		String newAnnotation = StringUtil.replaceFirst(
			annotation, StringPool.PERCENT, StringPool.BLANK, matcher.start());

		return StringUtil.replace(content, annotation, newAnnotation);
	}

	protected String checkAnnotationParameterProperties(
		String content, String annotation) {

		int x = annotation.indexOf("property = {");

		if (x == -1) {
			return content;
		}

		int y = x;

		while (true) {
			y = annotation.indexOf(CharPool.CLOSE_CURLY_BRACE, y + 1);

			if (!ToolsUtil.isInsideQuotes(annotation, y)) {
				break;
			}
		}

		String parameterProperties = annotation.substring(x + 12, y);

		parameterProperties = StringUtil.replace(
			parameterProperties, StringPool.NEW_LINE, StringPool.SPACE);

		String[] parameterPropertiesArray = StringUtil.split(
			parameterProperties, StringPool.COMMA_AND_SPACE);

		String previousPropertyName = null;
		String previousPropertyNameAndValue = null;

		for (String parameterProperty : parameterPropertiesArray) {
			x = parameterProperty.indexOf(CharPool.QUOTE);
			y = parameterProperty.indexOf(CharPool.EQUAL, x);

			int z = x;

			while (true) {
				z = parameterProperty.indexOf(CharPool.QUOTE, z + 1);

				if ((z == -1) ||
					!ToolsUtil.isInsideQuotes(parameterProperty, z)) {

					break;
				}
			}

			if ((x == -1) || (y == -1) || (z == -1)) {
				return content;
			}

			String propertyName = parameterProperty.substring(x + 1, y);
			String propertyNameAndValue = parameterProperty.substring(x + 1, z);

			if (Validator.isNotNull(previousPropertyName) &&
				(previousPropertyName.compareToIgnoreCase(propertyName) > 0)) {

				content = StringUtil.replaceFirst(
					content, previousPropertyNameAndValue,
					propertyNameAndValue);
				content = StringUtil.replaceLast(
					content, propertyNameAndValue,
					previousPropertyNameAndValue);

				return content;
			}

			previousPropertyName = propertyName;
			previousPropertyNameAndValue = propertyNameAndValue;
		}

		return content;
	}

	protected void checkAnnotationParameters(
		String fileName, String javaTermName, String annotation) {

		int x = annotation.indexOf(CharPool.OPEN_PARENTHESIS);

		String annotationParameters = stripQuotes(
			annotation.substring(x + 1), CharPool.QUOTE);

		x = -1;
		int y = -1;

		String previousParameterName = StringPool.BLANK;

		while (true) {
			x = annotationParameters.indexOf(CharPool.EQUAL, x + 1);

			if (x == -1) {
				return;
			}

			if (Validator.isNotNull(previousParameterName)) {
				y = annotationParameters.lastIndexOf(CharPool.COMMA, x);

				if (y == -1) {
					return;
				}
			}

			String parameterName = StringUtil.trim(
				annotationParameters.substring(y + 1, x));

			if (parameterName.startsWith(StringPool.OPEN_CURLY_BRACE)) {
				break;
			}

			if (Validator.isNull(previousParameterName) ||
				(previousParameterName.compareToIgnoreCase(parameterName) <=
					0)) {

				previousParameterName = parameterName;

				continue;
			}

			x = annotation.indexOf(CharPool.AT);
			y = annotation.indexOf(CharPool.OPEN_PARENTHESIS);

			if ((x == -1) || (x > y)) {
				return;
			}

			StringBundler sb = new StringBundler(8);

			sb.append("sort: ");

			if (Validator.isNotNull(javaTermName)) {
				sb.append(javaTermName);
				sb.append(StringPool.POUND);
			}

			String annotationName = annotation.substring(x, y);

			sb.append(annotationName);

			sb.append(StringPool.POUND);
			sb.append(parameterName);
			sb.append(StringPool.SPACE);
			sb.append(fileName);

			processErrorMessage(fileName, sb.toString());

			return;
		}
	}

	protected void checkBndInheritAnnotationOption() {
		for (Map.Entry<String, Tuple> entry :
				_bndInheritRequiredTupleMap.entrySet()) {

			String bndFileLocation = entry.getKey();

			Tuple bndInheritTuple = entry.getValue();

			String bndContent = (String)bndInheritTuple.getObject(0);
			boolean bndInheritRequired = (Boolean)bndInheritTuple.getObject(1);

			if (bndContent.contains("-dsannotations-options: inherit")) {
				/*if (!bndInheritRequired) {
					printError(
						bndFileLocation,
						"Redundant '-dsannotations-options: inherit': " +
							bndFileLocation + "bnd.bnd");
				}*/
			}
			else if (bndInheritRequired) {
				printError(
					bndFileLocation,
					"Add '-dsannotations-options: inherit': " +
						bndFileLocation + "bnd.bnd");
			}
		}
	}

	protected void checkDeserializationSecurity(
		String fileName, String content, boolean isRunOutsidePortalExclusion) {

		for (Pattern vulnerabilityPattern :
				_javaSerializationVulnerabilityPatterns) {

			Matcher matcher = vulnerabilityPattern.matcher(content);

			if (!matcher.matches()) {
				continue;
			}

			StringBundler sb = new StringBundler(5);

			if (isRunOutsidePortalExclusion) {
				sb.append("Possible Java Serialization Remote Code Execution ");
				sb.append("vulnerability using ");
			}
			else {
				sb.append("Use ProtectedObjectInputStream instead of ");
			}

			sb.append(matcher.group(1));
			sb.append(": ");
			sb.append(fileName);

			processErrorMessage(fileName, sb.toString());
		}
	}

	protected void checkFinderCacheInterfaceMethod(
		String fileName, String content) {

		if (!fileName.endsWith("FinderImpl.java") ||
			!content.contains("public static final FinderPath")) {

			return;
		}

		Matcher matcher = _fetchByPrimaryKeysMethodPattern.matcher(content);

		if (!matcher.find()) {
			processErrorMessage(
				fileName,
				"LPS-49552: Missing override of BasePersistenceImpl." +
					"fetchByPrimaryKeys(Set<Serializable>): " + fileName);
		}
	}

	protected void checkLogLevel(String content, String fileName) {
		if (fileName.contains("Log")) {
			return;
		}

		Matcher matcher = _logLevelPattern.matcher(content);

		while (matcher.find()) {
			int pos = matcher.start();

			while (true) {
				pos = content.lastIndexOf(
					StringPool.NEW_LINE + StringPool.TAB, pos - 1);

				char c = content.charAt(pos + 2);

				if (c != CharPool.TAB) {
					break;
				}
			}

			String codeBlock = content.substring(pos, matcher.start());
			String s =
				"_log.is" + StringUtil.upperCaseFirstLetter(matcher.group(2)) +
					"Enabled()";

			if (codeBlock.contains(s) ^ !s.equals("_log.isErrorEnabled()")) {
				int lineCount = getLineCount(content, matcher.start(1));

				if (codeBlock.contains(s)) {
					processErrorMessage(
						fileName,
						"Do not use _log.isErrorEnabled(): " + fileName + " " +
							lineCount);
				}
				else {
					processErrorMessage(
						fileName, "Use " + s + ": " + fileName + " " + lineCount);
				}
			}
		}
	}

	protected void checkMissingAuthor(
		String content, String fileName, String className) {

		if (!content.contains(" * @author ")) {
			processErrorMessage(fileName, "Missing author: " + fileName);
		}
	}

	protected void checkPackagePath(
		String content, String fileName, String packagePath) {

		if (!content.contains(
				"package " + packagePath + StringPool.SEMICOLON)) {

			processErrorMessage(
				fileName, "Incorrect package path: " + fileName);
		}
	}

	protected void checkRegexPattern(
		String regexPattern, String fileName, int lineCount) {

		int i = regexPattern.indexOf("Pattern.compile(");

		if (i == -1) {
			return;
		}

		regexPattern = regexPattern.substring(i + 16);

		regexPattern = stripQuotes(regexPattern, CharPool.QUOTE);

		i = regexPattern.indexOf(CharPool.COMMA);

		if (i != -1) {
			regexPattern = regexPattern.substring(0, i);
		}
		else {
			regexPattern = StringUtil.replaceLast(
				regexPattern, ");", StringPool.BLANK);
		}

		regexPattern = StringUtil.replace(
			regexPattern, StringPool.PLUS, StringPool.BLANK);

		if (Validator.isNull(regexPattern)) {
			processErrorMessage(
				fileName,
				"create pattern as global var: " + fileName + " " + lineCount);
		}
	}

	protected void checkSystemEventAnnotations(String content, String fileName)
		throws Exception {

		if (!portalSource || !fileName.endsWith("PortletDataHandler.java")) {
			return;
		}

		int pos = content.indexOf("setDeletionSystemEventStagedModelTypes");

		if (pos == -1) {
			return;
		}

		String deletionSystemEventStagedModelTypes = content.substring(
			pos, content.indexOf(");", pos));

		Matcher matcher = _stagedModelTypesPattern.matcher(
			deletionSystemEventStagedModelTypes);

		while (matcher.find()) {
			String stagedModelTypeClassName = matcher.group(1);

			pos = stagedModelTypeClassName.indexOf(".class");

			if (pos == -1) {
				pos = stagedModelTypeClassName.indexOf("Constants");
			}

			if (pos == -1) {
				return;
			}

			String className = stagedModelTypeClassName.substring(0, pos);

			Pattern packageNamePattern = Pattern.compile(
				"import (com\\.liferay\\.[a-zA-Z\\.]*)\\.model\\." +
					className + ";");

			Matcher packageNameMatcher = packageNamePattern.matcher(content);

			if (!packageNameMatcher.find()) {
				return;
			}

			StringBundler sb = new StringBundler(5);

			sb.append(fileName.substring(0, fileName.indexOf("/src/") + 5));
			sb.append(
				StringUtil.replace(
					packageNameMatcher.group(1), StringPool.PERIOD,
					StringPool.SLASH));
			sb.append("/service/impl/");
			sb.append(className);
			sb.append("LocalServiceImpl.java");

			String localServiceImplFileName = sb.toString();

			String localServiceImplContent = FileUtil.read(
				new File(localServiceImplFileName));

			if (localServiceImplContent == null) {
				return;
			}

			if (!localServiceImplContent.contains("@SystemEvent")) {
				processErrorMessage(
					fileName,
					"Missing deletion system event: " +
						localServiceImplFileName);
			}
		}
	}

	protected void checkXMLSecurity(
		String fileName, String content, boolean isRunOutsidePortalExclusion) {

		String[] xmlVulnerabitilies = new String[] {
			"DocumentBuilderFactory.newInstance",
			"new javax.xml.parsers.SAXParser",
			"new org.apache.xerces.parsers.SAXParser",
			"new org.dom4j.io.SAXReader", "new SAXParser", "new SAXReader",
			"SAXParserFactory.newInstance", "saxParserFactory.newInstance",
			"SAXParserFactory.newSAXParser", "saxParserFactory.newSAXParser",
			"XMLInputFactory.newFactory", "xmlInputFactory.newFactory",
			"XMLInputFactory.newInstance", "xmlInputFactory.newInstance"
		};

		for (String xmlVulnerabitily : xmlVulnerabitilies) {
			if (!content.contains(xmlVulnerabitily)) {
				continue;
			}

			StringBundler sb = new StringBundler(5);

			if (isRunOutsidePortalExclusion) {
				sb.append("Possible XXE or Quadratic Blowup security ");
				sb.append("vulnerability using ");
			}
			else {
				sb.append("Use SecureXMLBuilderUtil.newDocumentBuilderFactory");
				sb.append(" instead of ");
			}

			sb.append(xmlVulnerabitily);
			sb.append(": ");
			sb.append(fileName);

			processErrorMessage(fileName, sb.toString());
		}
	}

	@Override
	protected String doFormat(
			File file, String fileName, String absolutePath, String content)
		throws Exception {

		if (hasGeneratedTag(content)) {
			return content;
		}

		String className = file.getName();

		int pos = className.lastIndexOf(CharPool.PERIOD);

		className = className.substring(0, pos);

		String packagePath = ToolsUtil.getPackagePath(file);

		checkPackagePath(content, fileName, packagePath);

		if (packagePath.endsWith(".model")) {
			if (content.contains("extends " + className + "Model")) {
				return content;
			}
		}

		String newContent = trimContent(content, false);

		if (newContent.contains("$\n */")) {
			processErrorMessage(fileName, "*: " + fileName);

			newContent = StringUtil.replace(newContent, "$\n */", "$\n *\n */");
		}

		newContent = fixCopyright(
			newContent, absolutePath, fileName, className);

		if (newContent.contains(className + ".java.html")) {
			processErrorMessage(fileName, "Java2HTML: " + fileName);
		}

		if (newContent.contains(" * @author Raymond Aug") &&
			!newContent.contains(" * @author Raymond Aug\u00e9")) {

			newContent = newContent.replaceFirst(
				"Raymond Aug.++", "Raymond Aug\u00e9");

			processErrorMessage(fileName, "UTF-8: " + fileName);
		}

		checkMissingAuthor(newContent, fileName, className);

		newContent = fixDataAccessConnection(className, newContent);
		newContent = fixSessionKey(fileName, newContent, sessionKeyPattern);

		newContent = StringUtil.replace(
			newContent,
			new String[] {
				"com.liferay.portal.PortalException",
				"com.liferay.portal.SystemException",
				"com.liferay.util.LocalizationUtil"
			},
			new String[] {
				"com.liferay.portal.kernel.exception.PortalException",
				"com.liferay.portal.kernel.exception.SystemException",
				"com.liferay.portal.kernel.util.LocalizationUtil"
			});

		newContent = StringUtil.replace(
			newContent, " final static ", " static final ");

		newContent = fixCompatClassImports(absolutePath, newContent);

		ImportsFormatter importsFormatter = new JavaImportsFormatter();

		newContent = importsFormatter.format(
			newContent, packagePath, className);

		newContent = StringUtil.replace(
			newContent,
			new String[] {";\n/**", "\t/*\n\t *", ";;\n", "\n/**\n *\n *"},
			new String[] {";\n\n/**", "\t/**\n\t *", ";\n", "\n/**\n *"});

		newContent = fixMissingEmptyLines(newContent);

		newContent = fixRedundantEmptyLines(newContent);

		while (true) {
			Matcher matcher = _incorrectLineBreakPattern1.matcher(newContent);

			if (matcher.find()) {
				newContent = StringUtil.replaceFirst(
					newContent, StringPool.NEW_LINE, StringPool.BLANK,
					matcher.start());

				continue;
			}

			matcher = _incorrectLineBreakPattern2.matcher(newContent);

			if (matcher.find()) {
				newContent = StringUtil.replaceFirst(
					newContent, StringPool.NEW_LINE, StringPool.BLANK,
					matcher.start());

				continue;
			}

			matcher = _incorrectLineBreakPattern4.matcher(newContent);

			if (matcher.find()) {
				String matchingLine = matcher.group(2);

				if (!matchingLine.startsWith(StringPool.DOUBLE_SLASH) &&
					!matchingLine.startsWith(StringPool.STAR)) {

					newContent = StringUtil.replaceFirst(
						newContent, matcher.group(3),
						"\n" + matcher.group(1) + "}\n", matcher.start(3) - 1);

					continue;
				}
			}

			matcher = _incorrectLineBreakPattern5.matcher(newContent);

			if (matcher.find()) {
				String tabs = matcher.group(2);

				Pattern pattern = Pattern.compile(
					"\n" + tabs + "([^\t]{2})(?!.*\n" + tabs + "[^\t])",
					Pattern.DOTALL);

				Matcher matcher2 = pattern.matcher(
					newContent.substring(0, matcher.start(2)));

				if (matcher2.find()) {
					String match = matcher2.group(1);

					if (!match.equals(").")) {
						newContent = StringUtil.replaceFirst(
							newContent, "\n" + matcher.group(2), StringPool.BLANK,
							matcher.end(1));

						continue;
					}
				}
			}

			matcher = _redundantCommaPattern.matcher(newContent);

			if (matcher.find()) {
				newContent = StringUtil.replaceFirst(
					newContent, StringPool.COMMA, StringPool.BLANK,
					matcher.start());

				continue;
			}

			break;
		}

		Matcher matcher = _incorrectLineBreakPattern3.matcher(newContent);

		while (matcher.find()) {
			if (getLevel(matcher.group()) == 0) {
				int lineCount = getLineCount(newContent, matcher.start());

				processErrorMessage(
					fileName, "line break: " + fileName + " " + lineCount);
			}
		}

		newContent = formatAnnotations(
			fileName, StringPool.BLANK, newContent, StringPool.BLANK);

		matcher = _logPattern.matcher(newContent);

		if (matcher.find()) {
			String logClassName = matcher.group(1);

			if (!logClassName.equals(className)) {
				newContent = StringUtil.replaceLast(
					newContent, logClassName + ".class)",
					className + ".class)");
			}
		}

		if (!isExcludedPath(_staticLogVariableExcludes, absolutePath)) {
			newContent = StringUtil.replace(
				newContent, "private Log _log",
				"private static final Log _log");
		}

		if (newContent.contains("*/\npackage ")) {
			processErrorMessage(fileName, "package: " + fileName);
		}

		if (portalSource && !_allowUseServiceUtilInServiceImpl &&
			!className.equals("BaseServiceImpl") &&
			className.endsWith("ServiceImpl") &&
			newContent.contains("ServiceUtil.")) {

			processErrorMessage(fileName, "ServiceUtil: " + fileName);
		}

		// LPS-34911

		if (portalSource &&
			!isExcludedPath(_upgradeServiceUtilExcludes, absolutePath) &&
			fileName.contains("/portal/upgrade/") &&
			!fileName.contains("/test/") &&
			!fileName.contains("/testIntegration/") &&
			newContent.contains("ServiceUtil.")) {

			processErrorMessage(fileName, "ServiceUtil: " + fileName);
		}

		boolean isRunOutsidePortalExclusion = isExcludedPath(
			getRunOutsidePortalExcludes(), absolutePath);

		if (!isRunOutsidePortalExclusion &&
			!isExcludedPath(_proxyExcludes, absolutePath) &&
			newContent.contains("import java.lang.reflect.Proxy;")) {

			processErrorMessage(fileName, "Proxy: " + fileName);
		}

		if (newContent.contains("import edu.emory.mathcs.backport.java")) {
			processErrorMessage(
				fileName, "edu.emory.mathcs.backport.java: " + fileName);
		}

		if (newContent.contains("import jodd.util.StringPool")) {
			processErrorMessage(fileName, "jodd.util.StringPool: " + fileName);
		}

		// LPS-45027

		if (newContent.contains(
				"com.liferay.portal.kernel.util.UnmodifiableList")) {

			processErrorMessage(
				fileName,
				"Use java.util.Collections.unmodifiableList instead of " +
					"com.liferay.portal.kernel.util.UnmodifiableList: " +
						fileName);
		}

		// LPS-28266

		for (int pos1 = -1;;) {
			pos1 = newContent.indexOf(StringPool.TAB + "try {", pos1 + 1);

			if (pos1 == -1) {
				break;
			}

			int pos2 = newContent.indexOf(StringPool.TAB + "try {", pos1 + 1);
			int pos3 = newContent.indexOf("\"select count(", pos1);

			if ((pos2 != -1) && (pos3 != -1) && (pos2 < pos3)) {
				continue;
			}

			int pos4 = newContent.indexOf("rs.getLong(1)", pos1);
			int pos5 = newContent.indexOf(StringPool.TAB + "finally {", pos1);

			if ((pos3 == -1) || (pos4 == -1) || (pos5 == -1)) {
				break;
			}

			if ((pos3 < pos4) && (pos4 < pos5)) {
				processErrorMessage(
					fileName, "Use getInt(1) for count: " + fileName);
			}
		}

		// LPS-33070

		matcher = _processCallablePattern.matcher(content);

		if (matcher.find() &&
			!content.contains("private static final long serialVersionUID")) {

			processErrorMessage(
				fileName,
				"Assign ProcessCallable implementation a serialVersionUID: " +
					fileName);
		}

		checkLanguageKeys(
			fileName, absolutePath, newContent, languageKeyPattern);

		newContent = formatJSONObject(newContent);

		newContent = formatStringBundler(
			fileName, newContent, _MAX_LINE_LENGTH);

		newContent = StringUtil.replace(
			newContent, StringPool.TAB + "for (;;) {",
			StringPool.TAB + "while (true) {");

		newContent = formatExceptions(newContent, file, packagePath, fileName);

		// LPS-39508

		if (!isRunOutsidePortalExclusion &&
			!isExcludedPath(_secureRandomExcludes, absolutePath) &&
			content.contains("java.security.SecureRandom") &&
			!content.contains("javax.crypto.KeyGenerator")) {

			processErrorMessage(
				fileName,
				"Use SecureRandomUtil or com.liferay.portal.kernel.security." +
					"SecureRandom instead of java.security.SecureRandom: " +
						fileName);
		}

		// LPS-41315

		checkLogLevel(newContent, fileName);

		// LPS-46632

		checkSystemEventAnnotations(newContent, fileName);

		// LPS-41205

		if (fileName.contains("/upgrade/") &&
			newContent.contains("LocaleUtil.getDefault()")) {

			processErrorMessage(
				fileName,
				"Use UpgradeProcessUtil.getDefaultLanguageId(companyId) " +
					"instead of LocaleUtil.getDefault(): " + fileName);
		}

		// LPS-46017

		newContent = StringUtil.replace(
			newContent, " static interface ", " interface ");

		// LPS-47055

		newContent = fixSystemExceptions(newContent);

		// LPS-47648

		if (portalSource &&
			(fileName.contains("/test/integration/") ||
			 fileName.contains("/testIntegration/java"))) {

			newContent = StringUtil.replace(
				newContent, "FinderCacheUtil.clearCache();", StringPool.BLANK);
		}

		// LPS-47682

		newContent = fixIncorrectParameterTypeForLanguageUtil(
			newContent, false, fileName);

		if (portalSource && fileName.contains("/portal-kernel/") &&
			content.contains("import javax.servlet.jsp.")) {

			processErrorMessage(
				fileName,
				"Never import javax.servlet.jsp.* from portal-kernel " +
					fileName);
		}

		// LPS-48153

		if (!isExcludedPath(_diamondOperatorExcludes, absolutePath)) {
			newContent = applyDiamondOperator(newContent);
		}

		// LPS-49552

		checkFinderCacheInterfaceMethod(fileName, newContent);

		// LPS-50479

		if (!fileName.contains("/test/") &&
			!fileName.contains("/testIntegration/") &&
			!isExcludedPath(_secureXmlExcludes, absolutePath)) {

			checkXMLSecurity(fileName, content, isRunOutsidePortalExclusion);
		}

		// LPS-60358

		if (!fileName.contains("/test/") &&
			!fileName.contains("/testIntegration/") &&
			!isExcludedPath(_secureDeserializationExcludes, absolutePath)) {

			checkDeserializationSecurity(
				fileName, content, isRunOutsidePortalExclusion);
		}

		// LPS-55690

		if (newContent.contains("org.testng.Assert")) {
			processErrorMessage(
				fileName,
				"Use org.junit.Assert instead of org.testng.Assert: " +
					fileName);
		}

		if (portalSource && isModulesFile(absolutePath)) {
			newContent = formatModulesFile(
				fileName, absolutePath, className, packagePath, newContent);
		}

		// LPS-48156

		newContent = checkPrincipalException(newContent);

		// LPS-59828

		if (fileName.endsWith("Upgrade.java") &&
			newContent.contains("implements UpgradeStepRegistrator")) {

			matcher = _componentAnnotationPattern.matcher(newContent);

			if (matcher.find()) {
				String componentAnnotation = matcher.group();

				if (!componentAnnotation.contains("service =")) {
					processErrorMessage(
						fileName, "Missing service in @Component " + fileName);
				}
			}
		}

		// LPS-60473

		if (newContent.contains(".supportsBatchUpdates()") &&
			!fileName.endsWith("AutoBatchPreparedStatementUtil.java")) {

			processErrorMessage(
				fileName,
				"Use AutoBatchPreparedStatementUtil instead of " +
					"DatabaseMetaData.supportsBatchUpdates: " + fileName);
		}

		// LPS-64056

		if (newContent.contains("Configurable.createConfigurable(") &&
			!fileName.endsWith("ConfigurableUtil.java")) {

			processErrorMessage(
				fileName,
				"Use ConfigurableUtil.createConfigurable instead of " +
					"Configurable.createConfigurable: " + fileName);
		}

		// LPS-62786

		checkPropertyUtils(fileName, newContent);

		// LPS-63953

		if (!absolutePath.contains("poshi") &&
			!fileName.endsWith("StringUtilTest.java")) {

			checkStringUtilReplace(fileName, newContent);
		}

		newContent = formatAssertEquals(fileName, newContent);

		newContent = fixMissingEmptyLineAfterSettingVariable(newContent);

		newContent = getCombinedLinesContent(
			newContent, _combinedLinesPattern1);
		newContent = getCombinedLinesContent(
			newContent, _combinedLinesPattern2);

		newContent = formatArray(newContent);

		newContent = formatClassLine(newContent);

		newContent = fixIncorrectEmptyLineBeforeCloseCurlyBrace(
			newContent, fileName);

		pos = newContent.indexOf("\npublic ");

		if (pos != -1) {
			String javaClassContent = newContent.substring(pos + 1);

			int javaClassLineCount = getLineCount(newContent, pos + 1);

			newContent = formatJavaTerms(
				className, packagePath, file, fileName, absolutePath,
				newContent, javaClassContent, javaClassLineCount,
				_checkJavaFieldTypesExcludes,
				_javaTermAccessLevelModifierExcludes,
				_javaTermSortExcludes, _testAnnotationsExcludes);
		}

		newContent = formatJava(fileName, absolutePath, newContent);

		if (!isExcludedPath(_checkTabsExcludes, absolutePath)) {
			JavaSourceTabCalculator javaSourceTabCalculator =
				new JavaSourceTabCalculator();

			javaSourceTabCalculator.calculateTabs(
				fileName, newContent, (JavaSourceProcessor)this);
		}

		return StringUtil.replace(newContent, "\n\n\n", "\n\n");
	}

	@Override
	protected List<String> doGetFileNames() throws Exception {
		Collection<String> fileNames = null;

		if (portalSource) {
			fileNames = getPortalJavaFiles();

			_checkRegistryInTestClasses = GetterUtil.getBoolean(
				System.getProperty(
					"source.formatter.check.registry.in.test.classes"));
			_checkUnprocessedExceptions = GetterUtil.getBoolean(
				System.getProperty(
					"source.formatter.check.unprocessed.exceptions"));
		}
		else {
			fileNames = getPluginJavaFiles();
		}

		return new ArrayList<>(fileNames);
	}

	protected String fixDataAccessConnection(String className, String content) {
		int x = content.indexOf("package ");

		int y = content.indexOf(CharPool.SEMICOLON, x);

		if ((x == -1) || (y == -1)) {
			return content;
		}

		String packageName = content.substring(x + 8, y);

		if (!packageName.startsWith("com.liferay.portal.kernel.upgrade") &&
			!packageName.startsWith("com.liferay.portal.kernel.verify") &&
			!packageName.startsWith("com.liferay.portal.upgrade") &&
			!packageName.startsWith("com.liferay.portal.verify")) {

			return content;
		}

		content = StringUtil.replace(
			content, "DataAccess.getConnection",
			"DataAccess.getUpgradeOptimizedConnection");

		return content;
	}

	protected String fixIfClause(String ifClause, String line, int delta) {
		if (StringUtil.count(ifClause, line) > 1) {
			return ifClause;
		}

		String newLine = line;

		String whitespace = StringPool.BLANK;
		int whitespaceLength = Math.abs(delta);

		while (whitespaceLength > 0) {
			if (whitespaceLength >= 4) {
				whitespace += StringPool.TAB;

				whitespaceLength -= 4;
			}
			else {
				whitespace += StringPool.SPACE;

				whitespaceLength -= 1;
			}
		}

		if (delta > 0) {
			if (!line.contains(StringPool.TAB + whitespace)) {
				newLine = StringUtil.replaceLast(
					newLine, StringPool.TAB, StringPool.FOUR_SPACES);
			}

			newLine = StringUtil.replaceLast(
				newLine, StringPool.TAB + whitespace, StringPool.TAB);
		}
		else {
			newLine = StringUtil.replaceLast(
				newLine, StringPool.TAB, StringPool.TAB + whitespace);
		}

		newLine = StringUtil.replaceLast(
			newLine, StringPool.FOUR_SPACES, StringPool.TAB);

		return StringUtil.replace(ifClause, line, newLine);
	}

	protected String fixIncorrectEmptyLineBeforeCloseCurlyBrace(
		String content, String fileName) {

		Matcher matcher1 = _incorrectCloseCurlyBracePattern1.matcher(content);

		while (matcher1.find()) {
			String lastLine = StringUtil.trimLeading(matcher1.group(1));

			if (lastLine.startsWith("// ")) {
				continue;
			}

			String tabs = matcher1.group(2);
			int tabCount = tabs.length();

			int pos = matcher1.start();

			while (true) {
				pos = content.lastIndexOf("\n" + tabs, pos - 1);

				if (content.charAt(pos + tabCount + 1) == CharPool.TAB) {
					continue;
				}

				String codeBlock = content.substring(pos + 1, matcher1.end());

				String firstLine = codeBlock.substring(
					0, codeBlock.indexOf(CharPool.NEW_LINE) + 1);

				Matcher matcher2 = _incorrectCloseCurlyBracePattern2.matcher(
					firstLine);

				if (matcher2.find()) {
					break;
				}

				return StringUtil.replaceFirst(
					content, "\n\n" + tabs + "}\n", "\n" + tabs + "}\n", pos);
			}
		}

		return content;
	}

	protected String fixMissingEmptyLineAfterSettingVariable(String content) {
		Matcher matcher = _setVariablePattern.matcher(content);

		while (matcher.find()) {
			if (content.charAt(matcher.end()) == CharPool.NEW_LINE) {
				continue;
			}

			int x = content.indexOf(";\n", matcher.end());

			if (x == -1) {
				return content;
			}

			String nextCommand = content.substring(matcher.end() - 1, x + 1);

			if (nextCommand.contains("{\n")) {
				continue;
			}

			String variableName = matcher.group(1);

			Pattern pattern2 = Pattern.compile("\\W(" + variableName + ")\\.");

			Matcher matcher2 = pattern2.matcher(nextCommand);

			if (!matcher2.find()) {
				continue;
			}

			x = matcher2.start(1);

			if (ToolsUtil.isInsideQuotes(nextCommand, x)) {
				continue;
			}

			x += matcher.end();

			int y = content.lastIndexOf("\ttry (", x);

			if (y != -1) {
				int z = content.indexOf(") {\n", y);

				if (z > x) {
					continue;
				}
			}

			return StringUtil.replaceFirst(
				content, "\n", "\n\n", matcher.end(2));
		}

		return content;
	}

	protected String fixMissingEmptyLines(String content) {
		Matcher matcher = _missingEmptyLinePattern1.matcher(content);

		while (matcher.find()) {
			if (getLevel(matcher.group()) == 0) {
				content = StringUtil.replaceFirst(
					content, "\n", "\n\n", matcher.start());
			}
		}

		matcher = _missingEmptyLinePattern2.matcher(content);

		while (matcher.find()) {
			String match = matcher.group();

			if (!match.contains(StringPool.OPEN_PARENTHESIS)) {
				continue;
			}

			String whitespace = matcher.group(1);

			int x = content.indexOf(
				whitespace + StringPool.CLOSE_CURLY_BRACE + "\n",
				matcher.end());
			int y = content.indexOf(
				whitespace + StringPool.CLOSE_CURLY_BRACE + "\n\n",
				matcher.end());

			if ((x != -1) && (x != y)) {
				content = StringUtil.replaceFirst(content, "\n", "\n\n", x + 1);
			}
		}

		return content;
	}

	protected String fixRedundantEmptyLines(String content) {
		Matcher matcher = _redundantEmptyLines1.matcher(content);

		if (matcher.find()) {
			return StringUtil.replaceFirst(
				content, "\n", StringPool.BLANK, matcher.start());
		}

		matcher = _redundantEmptyLines2.matcher(content);

		if (matcher.find()) {
			return StringUtil.replaceFirst(
				content, "\n", StringPool.BLANK, matcher.end() - 1);
		}

		return content;
	}

	protected String fixSystemExceptions(String content) {
		if (!content.contains("SystemException")) {
			return content;
		}

		Matcher matcher = _throwsSystemExceptionPattern.matcher(content);

		if (!matcher.find()) {
			return content;
		}

		String match = matcher.group();
		String replacement = null;

		String afterException = matcher.group(3);
		String beforeException = matcher.group(2);

		if (Validator.isNull(beforeException) &&
			Validator.isNull(afterException)) {

			replacement = matcher.group(4);

			String beforeThrows = matcher.group(1);

			if (Validator.isNotNull(StringUtil.trim(beforeThrows))) {
				replacement = beforeThrows + replacement;
			}
		}
		else if (Validator.isNull(beforeException)) {
			replacement = StringUtil.replaceFirst(
				match, "SystemException, ", StringPool.BLANK);
		}
		else {
			replacement = StringUtil.replaceFirst(
				match, ", SystemException", StringPool.BLANK);
		}

		if (match.equals(replacement)) {
			return content;
		}

		return fixSystemExceptions(
			StringUtil.replaceFirst(content, match, replacement));
	}

	protected String formatAnnotations(
			String fileName, String javaTermName, String content, String indent)
		throws IOException {

		UnsyncBufferedReader unsyncBufferedReader = new UnsyncBufferedReader(
			new UnsyncStringReader(content));

		String line = null;

		String annotation = StringPool.BLANK;
		String previousAnnotation = StringPool.BLANK;

		while ((line = unsyncBufferedReader.readLine()) != null) {
			if (line.equals(indent + StringPool.CLOSE_CURLY_BRACE)) {
				return content;
			}

			if ((StringUtil.count(line, CharPool.TAB) == indent.length()) &&
				!line.startsWith(indent + StringPool.CLOSE_PARENTHESIS)) {

				if (Validator.isNotNull(annotation) &&
					annotation.contains(StringPool.OPEN_PARENTHESIS)) {

					Matcher matcher = _annotationPattern.matcher(annotation);

					if (matcher.find()) {
						String match = matcher.group();

						if ((getLevel(match) == 0) &&
							!match.endsWith("\n)\n") &&
							!match.endsWith("\t)\n")) {

							String tabs = matcher.group(1);

							String replacement = StringUtil.replaceLast(
								match, ")", "\n" + tabs + ")");

							return StringUtil.replace(
								content, match, replacement);
						}
					}

					String newContent = checkAnnotationParameterProperties(
						content, annotation);

					newContent = checkAnnotationMetaTypeProperties(
						newContent, annotation);

					if (!newContent.equals(content)) {
						return formatAnnotations(
							fileName, javaTermName, newContent, indent);
					}

					checkAnnotationParameters(
						fileName, javaTermName, annotation);
				}

				if (Validator.isNotNull(previousAnnotation) &&
					(previousAnnotation.compareToIgnoreCase(annotation) > 0)) {

					content = StringUtil.replaceFirst(
						content, previousAnnotation, annotation);
					content = StringUtil.replaceLast(
						content, annotation, previousAnnotation);

					return formatAnnotations(
						fileName, javaTermName, content, indent);
				}

				if (line.startsWith(indent + StringPool.AT)) {
					if (Validator.isNotNull(annotation)) {
						previousAnnotation = annotation;
					}

					annotation = line + "\n";
				}
				else {
					annotation = StringPool.BLANK;
					previousAnnotation = StringPool.BLANK;
				}
			}
			else {
				if (Validator.isNull(annotation)) {
					return content;
				}

				annotation += line + "\n";
			}
		}

		return content;
	}

	protected String formatArray(String content) {
		Matcher matcher = _arrayPattern.matcher(content);

		while (matcher.find()) {
			String newLine =
				matcher.group(3) + matcher.group(2) + matcher.group(4) +
					matcher.group(5);

			if (getLineLength(newLine) <= _MAX_LINE_LENGTH) {
				return StringUtil.replace(
					content, matcher.group(),
					matcher.group(1) + "\n" + newLine + "\n");
			}
		}

		return content;
	}

	protected String formatAssertEquals(String fileName, String content) {
		if (!fileName.endsWith("Test.java")) {
			return content;
		}

		Matcher matcher = _assertEqualsPattern.matcher(content);

		while (matcher.find()) {
			String parameters = StringUtil.trim(matcher.group(1));

			List<String> parametersList = splitParameters(parameters);

			if (parametersList.size() != 2) {
				continue;
			}

			String actualParameter = parametersList.get(1);

			String strippedQuotesActualParameter = stripQuotes(actualParameter);

			if (!actualParameter.startsWith("expected") &&
				!Validator.isDigit(actualParameter) &&
				Validator.isNotNull(strippedQuotesActualParameter)) {

				continue;
			}

			String assertEquals = matcher.group();
			String expectedParameter = parametersList.get(0);

			String newAssertEquals = StringUtil.replaceFirst(
				assertEquals, expectedParameter, actualParameter,
				assertEquals.indexOf(CharPool.OPEN_PARENTHESIS));

			newAssertEquals = StringUtil.replaceLast(
				newAssertEquals, actualParameter, expectedParameter);

			return StringUtil.replace(content, assertEquals, newAssertEquals);
		}

		return content;
	}

	protected String formatClassLine(String content) {
		Matcher matcher = _classPattern.matcher(content);

		while (matcher.find()) {
			String firstTrailingNonWhitespace = matcher.group(9);
			String match = matcher.group(1);
			String trailingWhitespace = matcher.group(8);

			if (!trailingWhitespace.contains("\n") &&
				!firstTrailingNonWhitespace.equals("}")) {

				return StringUtil.replace(content, match, match + "\n");
			}

			String formattedClassLine = getFormattedClassLine(
				matcher.group(2), match);

			if (formattedClassLine != null) {
				content = StringUtil.replace(
					content, match, formattedClassLine);
			}
		}

		return content;
	}

	protected String formatDuplicateReferenceMethods(
			String fileName, String content, String className,
			String packagePath)
		throws Exception {

		String moduleSuperClassContent = getModuleSuperClassContent(
			content, className, packagePath);

		if (Validator.isNull(moduleSuperClassContent) ||
			!moduleSuperClassContent.contains("@Component") ||
			!moduleSuperClassContent.contains("@Reference")) {

			setBNDInheritRequiredValue(fileName, false);

			return content;
		}

		boolean bndInheritRequired = false;

		Matcher matcher = _referenceMethodPattern.matcher(
			moduleSuperClassContent);

		while (matcher.find()) {
			String referenceMethod = matcher.group();

			int pos = content.indexOf(referenceMethod);

			if (pos != -1) {
				String referenceMethodContent = matcher.group(6);

				Matcher referenceMethodContentMatcher =
					_referenceMethodContentPattern.matcher(
						referenceMethodContent);

				if (referenceMethodContentMatcher.find()) {
					String variableName = referenceMethodContentMatcher.group(
						1);

					if (StringUtil.count(content, variableName) > 1) {
						continue;
					}
				}

				int x = content.lastIndexOf("\n\n", pos);
				int y = pos + referenceMethod.length();

				String entireMethod = content.substring(x + 1, y);

				content = StringUtil.replace(
					content, entireMethod, StringPool.BLANK);

				bndInheritRequired = true;
			}
			else {
				String referenceMethodModifierAndName = matcher.group(2);

				Pattern duplicateReferenceMethodPattern = Pattern.compile(
					referenceMethodModifierAndName +
						"\\(\\s*([ ,<>\\w]+)\\s+\\w+\\) \\{\\s+([\\s\\S]*?)" +
							"\\s*?\n\t\\}\n");

				Matcher duplicateReferenceMethodMatcher =
					duplicateReferenceMethodPattern.matcher(content);

				if (!duplicateReferenceMethodMatcher.find()) {
					bndInheritRequired = true;

					continue;
				}

				String methodContent = duplicateReferenceMethodMatcher.group(2);
				String referenceMethodName = matcher.group(4);

				if (methodContent.startsWith("super." + referenceMethodName)) {
					int x = content.lastIndexOf(
						"\n\n", duplicateReferenceMethodMatcher.start());
					int y = duplicateReferenceMethodMatcher.end();

					String entireMethod = content.substring(x + 1, y);

					content = StringUtil.replace(
						content, entireMethod, StringPool.BLANK);

					bndInheritRequired = true;
				}
			}
		}

		setBNDInheritRequiredValue(fileName, bndInheritRequired);

		return content;
	}

	protected String formatExceptions(
			String content, File file, String packagePath, String fileName)
		throws IOException {

		List<String> importedExceptionClassNames = null;
		JavaDocBuilder javaDocBuilder = null;

		Matcher matcher = _catchExceptionPattern.matcher(content);

		int skipVariableNameCheckEndPos = -1;

		while (matcher.find()) {
			String exceptionClassName = matcher.group(2);
			String exceptionVariableName = matcher.group(3);
			String tabs = matcher.group(1);

			String expectedExceptionVariableName = "e";

			if (!exceptionClassName.contains(" |")) {
				Matcher lowerCaseNumberOrPeriodMatcher =
					_lowerCaseNumberOrPeriodPattern.matcher(exceptionClassName);

				expectedExceptionVariableName = StringUtil.toLowerCase(
					lowerCaseNumberOrPeriodMatcher.replaceAll(
						StringPool.BLANK));
			}

			Pattern exceptionVariablePattern = Pattern.compile(
				"(\\W)" + exceptionVariableName + "(\\W)");

			int pos = content.indexOf(
				"\n" + tabs + StringPool.CLOSE_CURLY_BRACE, matcher.end() - 1);

			String insideCatchCode = content.substring(matcher.end(), pos + 1);

			if (insideCatchCode.contains("catch (" + exceptionClassName)) {
				skipVariableNameCheckEndPos = pos;
			}

			if ((skipVariableNameCheckEndPos < matcher.start()) &&
				!expectedExceptionVariableName.equals(exceptionVariableName)) {

				String catchExceptionCodeBlock = content.substring(
					matcher.start(), pos + 1);

				Matcher exceptionVariableMatcher =
					exceptionVariablePattern.matcher(catchExceptionCodeBlock);

				String catchExceptionReplacement =
					exceptionVariableMatcher.replaceAll(
						"$1" + expectedExceptionVariableName + "$2");

				return StringUtil.replaceFirst(
					content, catchExceptionCodeBlock, catchExceptionReplacement,
					matcher.start() - 1);
			}

			if (!_checkUnprocessedExceptions || fileName.contains("/test/") ||
				fileName.contains("/testIntegration/")) {

				continue;
			}

			// LPS-36174

			Matcher exceptionVariableMatcher = exceptionVariablePattern.matcher(
				insideCatchCode);

			if (exceptionVariableMatcher.find()) {
				continue;
			}

			if (javaDocBuilder == null) {
				javaDocBuilder = new JavaDocBuilder();

				javaDocBuilder.addSource(file);
			}

			if (importedExceptionClassNames == null) {
				importedExceptionClassNames = getImportedExceptionClassNames(
					javaDocBuilder);
			}

			String originalExceptionClassName = exceptionClassName;

			if (!exceptionClassName.contains(StringPool.PERIOD)) {
				for (String exceptionClass : importedExceptionClassNames) {
					if (exceptionClass.endsWith(
							StringPool.PERIOD + exceptionClassName)) {

						exceptionClassName = exceptionClass;

						break;
					}
				}
			}

			if (!exceptionClassName.contains(StringPool.PERIOD)) {
				exceptionClassName =
					packagePath + StringPool.PERIOD + exceptionClassName;
			}

			com.thoughtworks.qdox.model.JavaClass exceptionClass =
				javaDocBuilder.getClassByName(exceptionClassName);

			while (true) {
				String packageName = exceptionClass.getPackageName();

				if (!packageName.contains("com.liferay")) {
					break;
				}

				exceptionClassName = exceptionClass.getName();

				if (exceptionClassName.equals("PortalException") ||
					exceptionClassName.equals("SystemException")) {

					int lineCount = getLineCount(content, matcher.start(2));

					processErrorMessage(
						fileName,
						"Unprocessed " + originalExceptionClassName + ": " +
							fileName + " " + lineCount);

					break;
				}

				com.thoughtworks.qdox.model.JavaClass exceptionSuperClass =
					exceptionClass.getSuperJavaClass();

				if (exceptionSuperClass == null) {
					break;
				}

				exceptionClass = exceptionSuperClass;
			}
		}

		return content;
	}

	protected String formatIfClause(String ifClause) throws IOException {
		String strippedQuotesIfClause = stripQuotes(ifClause);

		if (strippedQuotesIfClause.contains("!(") ||
			strippedQuotesIfClause.contains("//")) {

			return ifClause;
		}

		UnsyncBufferedReader unsyncBufferedReader = new UnsyncBufferedReader(
			new UnsyncStringReader(ifClause));

		String line = null;

		String previousLine = null;
		int previousLineLength = 0;

		int previousLineLeadingWhitespace = 0;
		int previousLineLevel = 0;

		int baseLeadingWhitespace = 0;
		int insideMethodCallExpectedWhitespace = 0;
		int level = -1;

		while ((line = unsyncBufferedReader.readLine()) != null) {
			String originalLine = line;

			String trimmedLine = StringUtil.trimLeading(line);

			int x = getIncorrectLineBreakPos(line, previousLine);

			if (x != -1) {
				String leadingWhitespace = line.substring(
					0, line.indexOf(trimmedLine));

				return StringUtil.replace(
					ifClause, line,
					line.substring(0, x) + "\n" + leadingWhitespace +
						line.substring(x + 1));
			}

			line = StringUtil.replace(
				line, StringPool.TAB, StringPool.FOUR_SPACES);

			int lineLevel = getLevel(trimmedLine);

			if ((previousLineLength > 0) &&
				(line.endsWith("|") || line.endsWith("&&") ||
				 line.endsWith(") {")) &&
				(previousLine.endsWith("|") || previousLine.endsWith("&&")) &&
				(previousLineLength + trimmedLine.length() <
					_MAX_LINE_LENGTH) &&
				(lineLevel <= 0) && (previousLineLevel >= 0)) {

				return StringUtil.replace(
					ifClause, previousLine + "\n" + originalLine,
					previousLine + StringPool.SPACE + trimmedLine);
			}

			int leadingWhitespace = line.length() - trimmedLine.length();

			if (Validator.isNull(previousLine)) {
				baseLeadingWhitespace =
					line.indexOf(CharPool.OPEN_PARENTHESIS) + 1;
			}
			else if (previousLine.endsWith("|") || previousLine.endsWith("&") ||
					 previousLine.endsWith("^")) {

				int expectedLeadingWhitespace = baseLeadingWhitespace + level;

				if (leadingWhitespace != expectedLeadingWhitespace) {
					return fixIfClause(
						ifClause, originalLine,
						leadingWhitespace - expectedLeadingWhitespace);
				}
			}
			else {
				int expectedLeadingWhitespace = 0;

				if (previousLine.contains(StringPool.TAB + "else if (")) {
					expectedLeadingWhitespace = baseLeadingWhitespace + 3;
				}
				else if (previousLine.contains(StringPool.TAB + "if (")) {
					expectedLeadingWhitespace = baseLeadingWhitespace + 4;
				}
				else if (previousLine.contains(StringPool.TAB + "while (")) {
					expectedLeadingWhitespace = baseLeadingWhitespace + 5;
				}

				if (previousLine.endsWith(StringPool.COMMA) &&
					(insideMethodCallExpectedWhitespace > 0)) {

					if (previousLineLevel < 0) {
						insideMethodCallExpectedWhitespace -= 4;
					}

					expectedLeadingWhitespace =
						insideMethodCallExpectedWhitespace;
				}
				else {
					if (expectedLeadingWhitespace == 0) {
						expectedLeadingWhitespace =
							previousLineLeadingWhitespace + 4;
					}

					if (previousLine.endsWith(StringPool.OPEN_PARENTHESIS)) {
						insideMethodCallExpectedWhitespace =
							expectedLeadingWhitespace;
					}
				}

				if (leadingWhitespace != expectedLeadingWhitespace) {
					return fixIfClause(
						ifClause, originalLine,
						leadingWhitespace - expectedLeadingWhitespace);
				}
			}

			if (line.endsWith(") {")) {
				return ifClause;
			}

			level += lineLevel;

			previousLine = originalLine;
			previousLineLength = line.length();

			previousLineLevel = lineLevel;
			previousLineLeadingWhitespace = leadingWhitespace;
		}

		return ifClause;
	}

	protected String formatIfClause(
			String ifClause, String fileName, int lineCount)
		throws IOException {

		String ifClauseSingleLine = StringUtil.replace(
			ifClause,
			new String[] {
				StringPool.TAB + StringPool.SPACE, StringPool.TAB,
				StringPool.OPEN_PARENTHESIS + StringPool.NEW_LINE,
				StringPool.NEW_LINE
			},
			new String[] {
				StringPool.TAB, StringPool.BLANK, StringPool.OPEN_PARENTHESIS,
				StringPool.SPACE
			});

		checkIfClauseParentheses(ifClauseSingleLine, fileName, lineCount);

		return formatIfClause(ifClause);
	}

	protected String formatJava(
			String fileName, String absolutePath, String content)
		throws Exception {

		StringBundler sb = new StringBundler();

		try (UnsyncBufferedReader unsyncBufferedReader =
				new UnsyncBufferedReader(new UnsyncStringReader(content))) {

			String line = null;
			String previousLine = StringPool.BLANK;

			int lineCount = 0;

			String ifClause = StringPool.BLANK;
			String packageName = StringPool.BLANK;
			String regexPattern = StringPool.BLANK;

			while ((line = unsyncBufferedReader.readLine()) != null) {
				lineCount++;

				if (line.startsWith("package ")) {
					packageName = line.substring(8, line.length() - 1);
				}

				if (line.startsWith("import ")) {
					if (line.endsWith(".*;")) {
						processErrorMessage(
							fileName, "import: " + fileName + " " + lineCount);
					}

					int pos = line.lastIndexOf(CharPool.PERIOD);

					if (pos != -1) {
						String importPackageName = line.substring(7, pos);

						if (importPackageName.equals(packageName)) {
							continue;
						}
					}
				}

				String trimmedLine = StringUtil.trimLeading(line);

				if (!trimmedLine.startsWith(StringPool.DOUBLE_SLASH) &&
					!trimmedLine.startsWith(StringPool.STAR)) {

					if (line.contains(StringPool.TAB + StringPool.SPACE) &&
						!previousLine.endsWith("&&") &&
						!previousLine.endsWith("|") &&
						!previousLine.contains(StringPool.TAB + "((") &&
						!previousLine.contains(
							StringPool.TAB + StringPool.LESS_THAN) &&
						!previousLine.contains(
							StringPool.TAB + StringPool.SPACE) &&
						!previousLine.contains(StringPool.TAB + "for (") &&
						!previousLine.contains(
							StringPool.TAB + "implements ") &&
						!previousLine.contains(StringPool.TAB + "throws ")) {

						line = StringUtil.replace(
							line, StringPool.TAB + StringPool.SPACE,
							StringPool.TAB);
					}

					line = formatIncorrectSyntax(line, ",}", "}", false);

					line = formatWhitespace(line, trimmedLine, true);
				}

				if (line.contains(StringPool.TAB + "for (") &&
					line.contains(":") && !line.contains(" :")) {

					line = StringUtil.replace(line, ":", " :");
				}

				// LPS-42924

				if (line.contains("PortalUtil.getClassNameId(") &&
					fileName.endsWith("ServiceImpl.java")) {

					processErrorMessage(
						fileName,
						"Use classNameLocalService.getClassNameId: " +
							fileName + " " + lineCount);
				}

				// LPS-42599

				if (!isExcludedPath(_hibernateSQLQueryExcludes, absolutePath) &&
					line.contains("= session.createSQLQuery(") &&
					content.contains(
						"com.liferay.portal.kernel.dao.orm.Session")) {

					line = StringUtil.replace(
						line, "createSQLQuery", "createSynchronizedSQLQuery");
				}

				line = replacePrimitiveWrapperInstantiation(line);

				// LPS-45649

				if (trimmedLine.startsWith("throw new IOException(") &&
					line.contains("e.getMessage()")) {

					line = StringUtil.replace(
						line, ".getMessage()", StringPool.BLANK);
				}

				// LPS-45492

				if (trimmedLine.contains("StopWatch stopWatch = null;")) {
					processErrorMessage(
						fileName,
						"Do not set stopwatch to null: " + fileName + " " +
							lineCount);
				}

				checkEmptyCollection(trimmedLine, fileName, lineCount);

				line = formatEmptyArray(line);

				// LPS-58529

				checkResourceUtil(line, fileName, lineCount);

				if (trimmedLine.startsWith("* @deprecated") &&
					_addMissingDeprecationReleaseVersion) {

					if (!trimmedLine.startsWith("* @deprecated As of ")) {
						line = StringUtil.replace(
							line, "* @deprecated",
							"* @deprecated As of " + getMainReleaseVersion());
					}
					else {
						String version = trimmedLine.substring(20);

						version = StringUtil.split(
							version, StringPool.SPACE)[0];

						version = StringUtil.replace(
							version, StringPool.COMMA, StringPool.BLANK);

						if (StringUtil.count(version, CharPool.PERIOD) == 1) {
							line = StringUtil.replaceFirst(
								line, version, version + ".0");
						}
					}
				}

				if (trimmedLine.startsWith("* @see ") &&
					(StringUtil.count(trimmedLine, CharPool.AT) > 1)) {

					processErrorMessage(
						fileName,
						"Do not use @see with another annotation: " + fileName +
							" " + lineCount);
				}

				checkInefficientStringMethods(
					line, fileName, absolutePath, lineCount);

				if (trimmedLine.startsWith(StringPool.EQUAL)) {
					processErrorMessage(
						fileName, "line break: " + fileName + " " + lineCount);
				}

				if (trimmedLine.startsWith("},") && !trimmedLine.equals("},")) {
					processErrorMessage(
						fileName, "line break: " + fileName + " " + lineCount);
				}

				if (line.contains("ActionForm form")) {
					processErrorMessage(
						fileName,
						"Rename form to actionForm: " + fileName + " " +
							lineCount);
				}

				if (line.contains("ActionMapping mapping")) {
					processErrorMessage(
						fileName,
						"Rename mapping to ActionMapping: " + fileName + " " +
							lineCount);
				}

				if (fileName.contains("/upgrade/") &&
					line.contains("rs.getDate(")) {

					processErrorMessage(
						fileName,
						"Use rs.getTimeStamp: " + fileName + " " + lineCount);
				}

				if (!trimmedLine.equals("{") && line.endsWith("{") &&
					!line.endsWith(" {")) {

					line = StringUtil.replaceLast(line, "{", " {");
				}

				line = sortExceptions(line);

				if (trimmedLine.startsWith("Pattern ") ||
					Validator.isNotNull(regexPattern)) {

					regexPattern = regexPattern + trimmedLine;

					if (trimmedLine.endsWith(");")) {

						// LPS-41084

						checkRegexPattern(regexPattern, fileName, lineCount);

						regexPattern = StringPool.BLANK;
					}
				}

				int lineLeadingTabCount = getLeadingTabCount(line);
				int previousLineLeadingTabCount = getLeadingTabCount(
					previousLine);

				if (!trimmedLine.startsWith(StringPool.DOUBLE_SLASH) &&
					!trimmedLine.startsWith(StringPool.STAR)) {

					String strippedQuotesLine = stripQuotes(trimmedLine);

					int strippedQuotesLineOpenParenthesisCount =
						StringUtil.count(
							strippedQuotesLine, CharPool.OPEN_PARENTHESIS);

					if (!trimmedLine.startsWith(StringPool.OPEN_PARENTHESIS) &&
						trimmedLine.endsWith(") {") &&
						(strippedQuotesLineOpenParenthesisCount > 0) &&
						(getLevel(trimmedLine) > 0)) {

						processErrorMessage(
							fileName,
							"line break: " + fileName + " " + lineCount);
					}

					if (line.endsWith(StringPool.OPEN_PARENTHESIS)) {
						if (line.contains(" && ") || line.contains(" || ")) {
							processErrorMessage(
								fileName,
								"line break: " + fileName + " " + lineCount);
						}

						int pos = strippedQuotesLine.indexOf(" + ");

						if (pos != -1) {
							String linePart = strippedQuotesLine.substring(
								0, pos);

							if ((getLevel(linePart, "(", ")") == 0) &&
								(getLevel(linePart, "[", "]") == 0)) {

								processErrorMessage(
									fileName,
									"line break: " + fileName + " " +
										lineCount);
							}
						}
					}

					String indent = StringPool.BLANK;

					if (!trimmedLine.startsWith(StringPool.CLOSE_CURLY_BRACE) &&
						strippedQuotesLine.contains(
							StringPool.CLOSE_CURLY_BRACE)) {

						if ((getLevel(strippedQuotesLine, "{", "}") < 0) &&
							(lineLeadingTabCount > 0)) {

							for (int i = 0; i < lineLeadingTabCount - 1; i++) {
								indent += StringPool.TAB;
							}

							int x = line.lastIndexOf(
								CharPool.CLOSE_CURLY_BRACE);

							return StringUtil.replace(
								content, "\n" + line + "\n",
								"\n" + line.substring(0, x) + "\n" + indent +
									line.substring(x) + "\n");
						}
					}

					if (Validator.isNull(ifClause) &&
						!previousLine.contains("\tthrows ") &&
						!previousLine.contains(" throws ") &&
						(previousLineLeadingTabCount ==
							(lineLeadingTabCount - 1))) {

						int x = -1;

						while (true) {
							x = previousLine.indexOf(", ", x + 1);

							if (x == -1) {
								break;
							}

							if (ToolsUtil.isInsideQuotes(previousLine, x)) {
								continue;
							}

							String linePart = previousLine.substring(0, x);

							linePart = stripQuotes(linePart);

							if ((getLevel(linePart, "(", ")") != 0) ||
								(getLevel(linePart, "<", ">") != 0)) {

								continue;
							}

							linePart = previousLine.substring(x);

							linePart = stripQuotes(linePart, CharPool.QUOTE);

							if ((getLevel(linePart, "(", ")") != 0) ||
								(getLevel(linePart, "<", ">") != 0)) {

								continue;
							}

							if (Validator.isNull(indent)) {
								for (int i = 0; i < lineLeadingTabCount - 1;
										i++) {

									indent += StringPool.TAB;
								}
							}

							return StringUtil.replace(
								content, "\n" + previousLine + "\n",
								"\n" + previousLine.substring(0, x + 1) + "\n" +
									indent + previousLine.substring(x + 2) +
										"\n");
						}
					}

					if (previousLine.endsWith(StringPool.OPEN_PARENTHESIS) ||
						previousLine.endsWith(StringPool.PLUS)) {

						int x = -1;

						while (true) {
							x = strippedQuotesLine.indexOf(
								StringPool.COMMA_AND_SPACE, x + 1);

							if (x == -1) {
								break;
							}

							int level = getLevel(
								strippedQuotesLine.substring(0, x));

							if ((previousLine.endsWith(
									StringPool.OPEN_PARENTHESIS) &&
								 (level < 0)) ||
								(previousLine.endsWith(StringPool.PLUS) &&
								 (level <= 0))) {

								processErrorMessage(
									fileName,
									"line break: " + fileName + " " +
										lineCount);
							}
						}
					}

					int x = strippedQuotesLine.indexOf(", ");

					if (x != -1) {
						String linePart = strippedQuotesLine.substring(0, x);

						if (getLevel(linePart) < 0) {
							processErrorMessage(
								fileName,
								"line break: " + fileName + " " + lineCount);
						}
					}
					else if (trimmedLine.endsWith(StringPool.COMMA) &&
							 !trimmedLine.startsWith("for (")) {

						if (getLevel(trimmedLine) > 0) {
							processErrorMessage(
								fileName,
								"line break: " + fileName + " " + lineCount);
						}
					}

					if (line.endsWith(" +") || line.endsWith(" -") ||
						line.endsWith(" *") || line.endsWith(" /")) {

						x = line.indexOf(" = ");

						if (x != -1) {
							int y = line.indexOf(CharPool.QUOTE);

							if ((y == -1) || (x < y)) {
								processErrorMessage(
									fileName,
									"line break: " + fileName + " " +
										lineCount);
							}
						}
					}

					if (line.endsWith(" throws") ||
						(previousLine.endsWith(
							StringPool.OPEN_PARENTHESIS) &&
						 line.contains(" throws " ) &&
						 line.endsWith(StringPool.OPEN_CURLY_BRACE))) {

						processErrorMessage(
							fileName,
							"line break: " + fileName + " " + lineCount);
					}

					if (trimmedLine.startsWith(StringPool.PERIOD) ||
						(line.endsWith(StringPool.PERIOD) &&
						 line.contains(StringPool.EQUAL))) {

						processErrorMessage(
							fileName,
							"line break: " + fileName + " " + lineCount);
					}

					if (trimmedLine.matches("^\\} (catch|else|finally) .*")) {
						processErrorMessage(
							fileName,
							"line break: " + fileName + " " + lineCount);
					}
				}

				if (line.contains("    ") && !line.matches("\\s*\\*.*")) {
					if (!fileName.endsWith("StringPool.java")) {
						processErrorMessage(
							fileName, "tab: " + fileName + " " + lineCount);
					}
				}

				if (line.contains("  {") && !line.matches("\\s*\\*.*")) {
					processErrorMessage(
						fileName, "{:" + fileName + " " + lineCount);
				}

				if (trimmedLine.startsWith("catch (") ||
					trimmedLine.startsWith("if (") ||
					trimmedLine.startsWith("else if (") ||
					trimmedLine.startsWith("while (") ||
					Validator.isNotNull(ifClause)) {

					ifClause = ifClause + line + StringPool.NEW_LINE;

					if (line.endsWith(") {")) {
						String newIfClause = formatIfClause(
							ifClause, fileName, lineCount);

						if (!ifClause.equals(newIfClause) &&
							content.contains(ifClause)) {

							return StringUtil.replace(
								content, ifClause, newIfClause);
						}

						ifClause = StringPool.BLANK;
					}
					else if (line.endsWith(StringPool.SEMICOLON)) {
						String trimmedIfClause = StringUtil.trim(ifClause);

						if (!trimmedIfClause.startsWith("while ") &&
							!trimmedIfClause.contains("{\t")) {

							processErrorMessage(
								fileName,
								"Incorrect if statement: " + fileName + " " +
									lineCount);
						}

						ifClause = StringPool.BLANK;
					}
				}

				int lineLength = getLineLength(line);

				if (!line.startsWith("import ") &&
					!line.startsWith("package ") &&
					!line.matches("\\s*\\*.*")) {

					if (fileName.endsWith("Table.java") &&
						line.contains("String TABLE_SQL_CREATE = ")) {
					}
					else if (fileName.endsWith("Table.java") &&
							 line.contains("String TABLE_SQL_DROP = ")) {
					}
					else if (fileName.endsWith("Table.java") &&
							 line.contains(" index IX_")) {
					}
					else if (lineLength > _MAX_LINE_LENGTH) {
						if (!isExcludedPath(
								_lineLengthExcludes, absolutePath, lineCount) &&
							!isAnnotationParameter(content, trimmedLine)) {

							String truncateLongLinesContent =
								getTruncateLongLinesContent(
									content, line, trimmedLine, lineCount);

							if ((truncateLongLinesContent != null) &&
								!truncateLongLinesContent.equals(content)) {

								return truncateLongLinesContent;
							}

							processErrorMessage(
								fileName,
								"> 80: " + fileName + " " + lineCount);
						}
					}
					else {
						if (!trimmedLine.startsWith("//")) {
							if (previousLine.endsWith(StringPool.COMMA) &&
								previousLine.contains(
									StringPool.OPEN_PARENTHESIS) &&
								!previousLine.contains("for (") &&
								(lineLeadingTabCount >
									previousLineLeadingTabCount)) {

								processErrorMessage(
									fileName,
									"line break: " + fileName + " " +
										lineCount);
							}

							if ((lineLeadingTabCount ==
									previousLineLeadingTabCount) &&
								(previousLine.endsWith(StringPool.EQUAL) ||
								 previousLine.endsWith(
									 StringPool.OPEN_PARENTHESIS))) {

								processErrorMessage(
									fileName,
									"tab: " + fileName + " " + lineCount);
							}

							if (Validator.isNotNull(trimmedLine) &&
								previousLine.endsWith(
									StringPool.OPEN_CURLY_BRACE) &&
								!trimmedLine.startsWith(
									StringPool.CLOSE_CURLY_BRACE) &&
								((previousLineLeadingTabCount + 1) !=
									lineLeadingTabCount)) {

								processErrorMessage(
									fileName,
									"tab: " + fileName + " " + lineCount);
							}

							if (previousLine.endsWith(StringPool.PERIOD)) {
								int x = trimmedLine.indexOf(
									CharPool.OPEN_PARENTHESIS);

								if ((x != -1) &&
									((getLineLength(previousLine) + x) <
										_MAX_LINE_LENGTH) &&
									(trimmedLine.endsWith(
										StringPool.OPEN_PARENTHESIS) ||
									 (trimmedLine.charAt(x + 1) !=
										 CharPool.CLOSE_PARENTHESIS))) {

									processErrorMessage(
										fileName,
										"line break: " + fileName + " " +
											lineCount);
								}
							}

							int diff =
								lineLeadingTabCount -
								previousLineLeadingTabCount;

							if (trimmedLine.startsWith("throws ") &&
								((diff == 0) || (diff > 1))) {

								processErrorMessage(
									fileName,
									"tab: " + fileName + " " + lineCount);
							}

							if ((diff == 2) &&
								(previousLineLeadingTabCount > 0) &&
								line.endsWith(StringPool.SEMICOLON) &&
								!previousLine.contains(
									StringPool.TAB + "for (") &&
								!previousLine.contains(
									StringPool.TAB + "try (")) {

								line = StringUtil.replaceFirst(
									line, StringPool.TAB, StringPool.BLANK);
							}
						}

						String combinedLinesContent = getCombinedLinesContent(
							content, fileName, absolutePath, line, trimmedLine,
							lineLength, lineCount, previousLine,
							lineLeadingTabCount, previousLineLeadingTabCount);

						if ((combinedLinesContent != null) &&
							!combinedLinesContent.equals(content)) {

							return combinedLinesContent;
						}
					}
				}

				if (lineCount > 1) {
					sb.append(previousLine);

					if (Validator.isNotNull(previousLine) &&
						Validator.isNotNull(trimmedLine) &&
						!previousLine.contains("/*") &&
						!previousLine.endsWith("*/")) {

						String trimmedPreviousLine = StringUtil.trimLeading(
							previousLine);

						trimmedLine = StringUtil.trimLeading(line);

						if ((trimmedPreviousLine.startsWith("// ") &&
							 !trimmedLine.startsWith("// ")) ||
							(!trimmedPreviousLine.startsWith("// ") &&
							 trimmedLine.startsWith("// "))) {

							sb.append("\n");
						}
						else if (!trimmedPreviousLine.endsWith(
									StringPool.OPEN_CURLY_BRACE) &&
								 !trimmedPreviousLine.endsWith(
									 StringPool.COLON) &&
								 (trimmedLine.startsWith("for (") ||
								  trimmedLine.startsWith("if (") ||
								  trimmedLine.startsWith("try {"))) {

							sb.append("\n");
						}
						else if (previousLine.endsWith(
									StringPool.TAB +
										StringPool.CLOSE_CURLY_BRACE) &&
								 !trimmedLine.startsWith(
									 StringPool.CLOSE_CURLY_BRACE) &&
								 !trimmedLine.startsWith(
									 StringPool.CLOSE_PARENTHESIS) &&
								 !trimmedLine.startsWith(
									 StringPool.DOUBLE_SLASH) &&
								 !trimmedLine.equals("*/") &&
								 !trimmedLine.startsWith("catch ") &&
								 !trimmedLine.startsWith("else ") &&
								 !trimmedLine.startsWith("finally ") &&
								 !trimmedLine.startsWith("while ")) {

							sb.append("\n");
						}
					}

					sb.append("\n");
				}

				previousLine = line;
			}

			sb.append(previousLine);
		}

		String newContent = sb.toString();

		if (newContent.endsWith("\n")) {
			newContent = newContent.substring(0, newContent.length() - 1);
		}

		return newContent;
	}

	protected String formatModulesFile(
			String fileName, String absolutePath, String className,
			String packagePath, String content)
		throws Exception {

		// LPS-56706 and LPS-57722

		if (fileName.endsWith("Test.java")) {
			if (absolutePath.contains("/src/testIntegration/java/") ||
				absolutePath.contains("/test/integration/")) {

				if (content.contains("@RunWith(Arquillian.class)") &&
					content.contains("import org.powermock.")) {

					processErrorMessage(
						fileName,
						"Do not use PowerMock inside Arquillian tests: " +
							fileName);
				}

				if (!packagePath.endsWith(".test")) {
					processErrorMessage(
						fileName,
						"Module integration test must be under a test " +
							"subpackage" + fileName);
				}
			}
			else if ((absolutePath.contains("/test/unit/") ||
					  absolutePath.contains("/src/test/java/")) &&
					 packagePath.endsWith(".test")) {

				processErrorMessage(
					fileName,
					"Module unit test should not be under a test subpackage" +
						fileName);
			}
		}

		// LPS-57358

		if (content.contains("ProxyFactory.newServiceTrackedInstance(")) {
			processErrorMessage(
				fileName,
				"Do not use ProxyFactory.newServiceTrackedInstance in " +
					"modules: " + fileName);
		}

		// LPS-59076

		if (content.contains("@Component")) {
			content = formatOSGIComponents(
				fileName, absolutePath, content, className, packagePath);
		}

		// LPS-62989

		if (!absolutePath.contains("/modules/core/jaxws-osgi-bridge") &&
			!absolutePath.contains("/modules/core/portal-bootstrap") &&
			!absolutePath.contains("/modules/core/registry-") &&
			(_checkRegistryInTestClasses ||
			 (!absolutePath.contains("/test/") &&
			  !absolutePath.contains("/testIntegration/")))) {

			Matcher matcher = _registryImportPattern.matcher(content);

			if (matcher.find()) {
				processErrorMessage(
					fileName,
					"Do not use com.liferay.registry classes in modules: " +
						fileName);
			}
		}

		// LPS-60186

		if (!absolutePath.contains("/test/") && content.contains("@Meta.OCD") &&
			!content.contains("@ExtendedObjectClassDefinition")) {

			processErrorMessage(
				fileName,
				"Specify category using @ExtendedObjectClassDefinition: " +
					fileName);
		}

		// LPS-64238

		if (content.contains("import com.liferay.util.dao.orm.CustomSQLUtil")) {
			processErrorMessage(
				fileName,
				"Do not use com.liferay.util.dao.orm.CustomSQLUtil in " +
					"modules: " + fileName);
		}

		// LPS-64335

		if (content.contains("import com.liferay.util.ContentUtil")) {
			processErrorMessage(
				fileName,
				"Do not use com.liferay.util.ContentUtil in modules:" +
					fileName);
		}

		return content;
	}

	protected String formatOSGIComponents(
			String fileName, String absolutePath, String content,
			String className, String packagePath)
		throws Exception {

		String moduleServicePackagePath = null;

		Matcher matcher = _serviceUtilImportPattern.matcher(content);

		while (matcher.find()) {
			String serviceUtilClassName = matcher.group(2);

			if (moduleServicePackagePath == null) {
				moduleServicePackagePath = getModuleServicePackagePath(
					fileName);
			}

			if (Validator.isNotNull(moduleServicePackagePath)) {
				String serviceUtilClassPackagePath = matcher.group(1);

				if (serviceUtilClassPackagePath.startsWith(
						moduleServicePackagePath)) {

					continue;
				}
			}

			processErrorMessage(
				fileName,
				"LPS-59076: Use @Reference instead of calling " +
					serviceUtilClassName + " directly: " + fileName);
		}

		matcher = _referenceMethodPattern.matcher(content);

		while (matcher.find()) {
			String methodName = matcher.group(4);

			if (!methodName.startsWith("set")) {
				continue;
			}

			String annotationParameters = matcher.group(1);

			if (!annotationParameters.contains("unbind =")) {
				if (!content.contains("un" + methodName + "(")) {
					if (Validator.isNull(annotationParameters)) {
						return StringUtil.insert(
							content, "(unbind = \"-\")", matcher.start(1));
					}

					if (!annotationParameters.contains(StringPool.NEW_LINE)) {
						return StringUtil.insert(
							content, ", unbind = \"-\"", matcher.end(1) - 1);
					}

					if (!annotationParameters.contains("\n\n")) {
						String indent = "\t\t";

						int x = content.lastIndexOf("\n", matcher.end(1) - 1);

						return StringUtil.replaceFirst(
							content, "\n",
							",\n" + indent + "unbind = \"-\"" + "\n", x - 1);
					}
				}
			}

			String methodContent = matcher.group(6);

			Matcher referenceMethodContentMatcher =
				_referenceMethodContentPattern.matcher(methodContent);

			if (!referenceMethodContentMatcher.find()) {
				continue;
			}

			String typeName = matcher.group(5);
			String variableName = referenceMethodContentMatcher.group(1);

			StringBundler sb = new StringBundler(5);

			sb.append("private volatile ");
			sb.append(typeName);
			sb.append("\\s+");
			sb.append(variableName);
			sb.append(StringPool.SEMICOLON);

			Pattern privateVarPattern = Pattern.compile(sb.toString());

			Matcher privateVarMatcher = privateVarPattern.matcher(content);

			if (privateVarMatcher.find()) {
				String match = privateVarMatcher.group();

				String replacement = StringUtil.replace(
					match, "private volatile ", "private ");

				return StringUtil.replace(content, match, replacement);
			}
		}

		return formatDuplicateReferenceMethods(
			fileName, content, className, packagePath);
	}

	protected String getCombinedLinesContent(String content, Pattern pattern) {
		Matcher matcher = pattern.matcher(content);

		while (matcher.find()) {
			String tabs = matcher.group(1);

			int x = matcher.start(1);

			String openChar = matcher.group(matcher.groupCount());

			int y = -1;

			if (openChar.equals(StringPool.OPEN_CURLY_BRACE)) {
				y = content.indexOf(
					StringPool.NEW_LINE + tabs + StringPool.CLOSE_CURLY_BRACE,
					x);
			}
			else if (openChar.equals(StringPool.OPEN_PARENTHESIS)) {
				y = content.indexOf(
					StringPool.NEW_LINE + tabs + StringPool.CLOSE_PARENTHESIS,
					x);
			}

			y = content.indexOf(CharPool.NEW_LINE, y + 1);

			if (y < x) {
				return content;
			}

			String match = content.substring(x, y);

			String replacement = match;

			while (replacement.contains("\n\t")) {
				replacement = StringUtil.replace(replacement, "\n\t", "\n");
			}

			replacement = StringUtil.replace(
				replacement, new String[] {",\n", "\n"},
				new String[] {StringPool.COMMA_AND_SPACE, StringPool.BLANK});

			if (getLineLength(replacement) <= _MAX_LINE_LENGTH) {
				return getCombinedLinesContent(
					StringUtil.replace(content, match, replacement), pattern);
			}
		}

		return content;
	}

	protected String getCombinedLinesContent(
		String content, String fileName, String line, String trimmedLine,
		int lineLength, int lineCount, String previousLine, String linePart,
		int tabDiff, boolean addToPreviousLine, boolean extraSpace,
		int numNextLinesRemoveLeadingTab) {

		int previousLineStartPos = getLineStartPos(content, lineCount - 1);

		if (linePart == null) {
			String combinedLine = previousLine;

			if (extraSpace) {
				combinedLine += StringPool.SPACE;
			}

			combinedLine += trimmedLine;

			String nextLine = getLine(content, lineCount + 1);

			if (nextLine == null) {
				return null;
			}

			if (line.endsWith(StringPool.OPEN_CURLY_BRACE) &&
				(tabDiff != 0) && !previousLine.contains(" class ") &&
				Validator.isNull(nextLine)) {

				return StringUtil.replaceFirst(
					content, previousLine + "\n" + line + "\n", combinedLine,
					previousLineStartPos);
			}

			if (numNextLinesRemoveLeadingTab > 0) {
				int nextLineStartPos = getLineStartPos(content, lineCount + 1);

				for (int i = 0; i < numNextLinesRemoveLeadingTab; i++) {
					content = StringUtil.replaceFirst(
						content, StringPool.TAB, StringPool.BLANK,
						nextLineStartPos);

					nextLineStartPos =
						content.indexOf(CharPool.NEW_LINE, nextLineStartPos) +
							1;
				}
			}

			return StringUtil.replaceFirst(
				content, previousLine + "\n" + line, combinedLine,
				previousLineStartPos);
		}

		String firstLine = previousLine;
		String secondLine = line;

		if (addToPreviousLine) {
			if (extraSpace) {
				firstLine += StringPool.SPACE;
			}

			firstLine += linePart;

			secondLine = StringUtil.replaceFirst(
				line, linePart, StringPool.BLANK);
		}
		else {
			if (((linePart.length() + lineLength) <= _MAX_LINE_LENGTH) &&
				(line.endsWith(StringPool.OPEN_CURLY_BRACE) ||
				 line.endsWith(StringPool.SEMICOLON))) {

				firstLine = StringUtil.replaceLast(
					firstLine, StringUtil.trim(linePart), StringPool.BLANK);

				if (extraSpace) {
					secondLine = StringUtil.replaceLast(
						line, StringPool.TAB,
						StringPool.TAB + linePart + StringPool.SPACE);
				}
				else {
					secondLine = StringUtil.replaceLast(
						line, StringPool.TAB, StringPool.TAB + linePart);
				}
			}
			else {
				processErrorMessage(
					fileName, "line break: " + fileName + " " + lineCount);

				return null;
			}
		}

		firstLine = StringUtil.trimTrailing(firstLine);

		return StringUtil.replaceFirst(
			content, previousLine + "\n" + line, firstLine + "\n" + secondLine,
			previousLineStartPos);
	}

	protected String getCombinedLinesContent(
		String content, String fileName, String absolutePath, String line,
		String trimmedLine, int lineLength, int lineCount, String previousLine,
		int lineTabCount, int previousLineTabCount) {

		if (Validator.isNull(line) || Validator.isNull(previousLine) ||
			isExcludedPath(_fitOnSingleLineExcludes, absolutePath, lineCount)) {

			return null;
		}

		String trimmedPreviousLine = StringUtil.trimLeading(previousLine);

		if (line.contains("// ") || line.contains("*/") ||
			line.contains("*/") || previousLine.contains("// ") ||
			previousLine.contains("*/") || previousLine.contains("*/")) {

			return null;
		}

		int tabDiff = lineTabCount - previousLineTabCount;

		if (previousLine.endsWith("= new")) {
			return getCombinedLinesContent(
				content, fileName, line, trimmedLine, lineLength, lineCount,
				previousLine, "new", tabDiff, false, true, 0);
		}

		if (trimmedLine.startsWith("+ ") || trimmedLine.startsWith("- ") ||
			trimmedLine.startsWith("|| ") || trimmedLine.startsWith("&& ")) {

			int pos = trimmedLine.indexOf(CharPool.SPACE);

			String linePart = trimmedLine.substring(0, pos);

			return getCombinedLinesContent(
				content, fileName, line, trimmedLine, lineLength, lineCount,
				previousLine, linePart, tabDiff, true, true, 0);
		}

		if (previousLine.endsWith("<") && !previousLine.endsWith(" <")) {
			return getCombinedLinesContent(
				content, fileName, line, trimmedLine, lineLength, lineCount,
				previousLine, "<", tabDiff, false, false, 0);
		}

		int previousLineLength = getLineLength(previousLine);

		if ((trimmedLine.length() + previousLineLength) < _MAX_LINE_LENGTH) {
			if (trimmedPreviousLine.startsWith("for ") &&
				previousLine.endsWith(StringPool.COLON) &&
				line.endsWith(StringPool.OPEN_CURLY_BRACE)) {

				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength, lineCount,
					previousLine, null, tabDiff, false, true, 0);
			}

			if (line.endsWith(StringPool.SEMICOLON) &&
				!previousLine.endsWith(StringPool.COLON) &&
				!previousLine.endsWith(StringPool.OPEN_BRACKET) &&
				!previousLine.endsWith(StringPool.OPEN_CURLY_BRACE) &&
				!previousLine.endsWith(StringPool.OPEN_PARENTHESIS) &&
				!previousLine.endsWith(StringPool.PERIOD) &&
				(previousLine.contains("[") || !previousLine.contains("]")) &&
				(lineTabCount == (previousLineTabCount + 1))) {

				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength, lineCount,
					previousLine, null, tabDiff, false, true, 0);
			}

			if ((trimmedPreviousLine.startsWith("if ") ||
				 trimmedPreviousLine.startsWith("else ")) &&
				(previousLine.endsWith("||") || previousLine.endsWith("&&")) &&
				line.endsWith(StringPool.OPEN_CURLY_BRACE)) {

				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength, lineCount,
					previousLine, null, tabDiff, false, true, 0);
			}

			if (trimmedLine.startsWith("throws") &&
				(line.endsWith(StringPool.OPEN_CURLY_BRACE) ||
				 line.endsWith(StringPool.SEMICOLON)) &&
				(lineTabCount == (previousLineTabCount + 1))) {

				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength, lineCount,
					previousLine, null, tabDiff, false, true, 0);
			}

			if (previousLine.endsWith(StringPool.EQUAL)) {
				if (line.endsWith(StringPool.OPEN_CURLY_BRACE)) {
					processErrorMessage(
						fileName, "line break: " + fileName + " " + lineCount);

					return null;
				}

				if (previousLine.endsWith(" =") &&
					line.endsWith(StringPool.OPEN_PARENTHESIS)) {

					for (int i = 0;; i++) {
						String nextLine = getLine(content, lineCount + i + 1);

						if (Validator.isNull(nextLine) ||
							nextLine.endsWith(") {")) {

							processErrorMessage(
								fileName,
								"line break: " + fileName + " " + lineCount);

							return null;
						}

						if (nextLine.endsWith(") +")) {
							return null;
						}

						if (nextLine.endsWith(StringPool.SEMICOLON)) {
							return getCombinedLinesContent(
								content, fileName, line, trimmedLine,
								lineLength, lineCount, previousLine, null,
								tabDiff, false, true, i + 1);
						}
					}
				}
			}

			if (trimmedPreviousLine.equals("return") &&
				line.endsWith(StringPool.OPEN_PARENTHESIS)) {

				for (int i = 0;; i++) {
					String nextLine = getLine(content, lineCount + i + 1);

					if (nextLine.endsWith(StringPool.SEMICOLON)) {
						return getCombinedLinesContent(
							content, fileName, line, trimmedLine,
							lineLength, lineCount, previousLine, null,
							tabDiff, false, true, i + 1);
					}
				}
			}
		}

		if (((trimmedLine.length() + previousLineLength) <= _MAX_LINE_LENGTH) &&
			(previousLine.endsWith(StringPool.OPEN_BRACKET) ||
			 previousLine.endsWith(StringPool.OPEN_PARENTHESIS) ||
			 previousLine.endsWith(StringPool.PERIOD)) &&
			line.endsWith(StringPool.SEMICOLON)) {

			return getCombinedLinesContent(
				content, fileName, line, trimmedLine, lineLength, lineCount,
				previousLine, null, tabDiff, false, false, 0);
		}

		if (previousLine.endsWith(StringPool.EQUAL) &&
			line.endsWith(StringPool.SEMICOLON)) {

			String tempLine = trimmedLine;

			for (int pos = 0;;) {
				pos = tempLine.indexOf(CharPool.DASH);

				if (pos == -1) {
					pos = tempLine.indexOf(CharPool.PLUS);
				}

				if (pos == -1) {
					pos = tempLine.indexOf(CharPool.SLASH);
				}

				if (pos == -1) {
					pos = tempLine.indexOf(CharPool.STAR);
				}

				if (pos == -1) {
					pos = tempLine.indexOf("||");
				}

				if (pos == -1) {
					pos = tempLine.indexOf("&&");
				}

				if (pos == -1) {
					break;
				}

				String linePart = tempLine.substring(0, pos);

				if (getLevel(linePart) == 0) {
					return null;
				}

				tempLine =
					tempLine.substring(0, pos) + tempLine.substring(pos + 1);
			}

			int x = trimmedLine.indexOf(CharPool.OPEN_PARENTHESIS);

			if (x == 0) {
				x = trimmedLine.indexOf(CharPool.OPEN_PARENTHESIS, 1);
			}

			if (x != -1) {
				int y = trimmedLine.indexOf(CharPool.CLOSE_PARENTHESIS, x);
				int z = trimmedLine.indexOf(CharPool.QUOTE);

				if (((x + 1) != y) && ((z == -1) || (z > x))) {
					char previousChar = trimmedLine.charAt(x - 1);

					if ((previousChar != CharPool.CLOSE_PARENTHESIS) &&
						(previousChar != CharPool.OPEN_PARENTHESIS) &&
						(previousChar != CharPool.SPACE) &&
						(previousLineLength + 1 + x) < _MAX_LINE_LENGTH) {

						String linePart = trimmedLine.substring(0, x + 1);

						if (getLevel(linePart, "{", "}") > 0) {
							return null;
						}

						if (linePart.startsWith(StringPool.OPEN_PARENTHESIS) &&
							!linePart.contains(
								StringPool.CLOSE_PARENTHESIS)) {

							return null;
						}

						return getCombinedLinesContent(
							content, fileName, line, trimmedLine, lineLength,
							lineCount, previousLine, linePart, tabDiff, true,
							true, 0);
					}
				}
			}
		}

		if (previousLine.endsWith(StringPool.PLUS) &&
			(lineTabCount == (previousLineTabCount + 1))) {

			int x = -1;

			while (true) {
				x = trimmedLine.indexOf(" +", x + 1);

				if ((x == -1) ||
					(previousLineLength + 3 + x) > _MAX_LINE_LENGTH) {

					break;
				}

				if (ToolsUtil.isInsideQuotes(trimmedLine, x)) {
					continue;
				}

				String linePart = trimmedLine.substring(0, x + 2);

				if (getLevel(linePart) != 0) {
					continue;
				}

				if (trimmedLine.equals(linePart)) {
					processErrorMessage(
						fileName,
						"line break: " + fileName + " " + lineCount);

					return null;
				}

				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength, lineCount,
					previousLine, linePart + StringPool.SPACE, tabDiff, true,
					true, 0);
			}
		}

		if (previousLine.endsWith(StringPool.COMMA) &&
			(previousLineTabCount == lineTabCount) &&
			!trimmedPreviousLine.equals("},")) {

			String nextLine = getLine(content, lineCount + 1);

			int nextLineTabCount = getLeadingTabCount(nextLine);

			if (nextLineTabCount != (lineTabCount + 1)) {
				int x = trimmedLine.indexOf(CharPool.COMMA);

				if (x != -1) {
					while ((previousLineLength + 1 + x) < _MAX_LINE_LENGTH) {
						String linePart = trimmedLine.substring(0, x + 1);

						if (isValidJavaParameter(linePart)) {
							if (trimmedLine.equals(linePart)) {
								return getCombinedLinesContent(
									content, fileName, line, trimmedLine,
									lineLength, lineCount, previousLine, null,
									tabDiff, false, true, 0);
							}
							else {
								return getCombinedLinesContent(
									content, fileName, line, trimmedLine,
									lineLength, lineCount, previousLine,
									linePart + StringPool.SPACE, tabDiff, true,
									true, 0);
							}
						}

						String partAfterComma = trimmedLine.substring(x + 1);

						int pos = partAfterComma.indexOf(CharPool.COMMA);

						if (pos == -1) {
							break;
						}

						x = x + pos + 1;
					}
				}
				else if ((trimmedLine.length() + previousLineLength) <
							_MAX_LINE_LENGTH) {

					if (!trimmedLine.startsWith("new ") ||
						!line.endsWith(StringPool.OPEN_CURLY_BRACE)) {

						return getCombinedLinesContent(
							content, fileName, line, trimmedLine, lineLength,
							lineCount, previousLine, null, tabDiff, false, true,
							0);
					}

					if (getLevel(line) != 0) {
						return getCombinedLinesContent(
							content, fileName, line, trimmedLine, lineLength,
							lineCount, previousLine, null, tabDiff, false, true,
							0);
					}
				}
			}
		}

		if (trimmedPreviousLine.matches("^[^<].*[\\w>]$") &&
			(previousLineTabCount == (lineTabCount - 1)) &&
			(getLevel(previousLine, "<", ">") == 0)) {

			int x = trimmedLine.indexOf(" = ");

			if ((x != -1) && !ToolsUtil.isInsideQuotes(trimmedLine, x) &&
				((previousLineLength + 2 + x) < _MAX_LINE_LENGTH)) {

				String linePart = trimmedLine.substring(0, x + 3);

				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength,
					lineCount, previousLine, linePart, tabDiff, true, true, 0);
			}
			else if (trimmedLine.endsWith(" =") &&
					 ((trimmedLine.length() + previousLineLength) <
						_MAX_LINE_LENGTH)) {

				for (int i = 0;; i++) {
					String nextLine = getLine(content, lineCount + i + 1);

					if (nextLine.endsWith(StringPool.SEMICOLON)) {
						return getCombinedLinesContent(
							content, fileName, line, trimmedLine, lineLength,
							lineCount, previousLine, null, tabDiff, false, true,
							i + 1);
					}
				}
			}
		}

		if (!previousLine.endsWith(StringPool.OPEN_PARENTHESIS)) {
			return null;
		}

		if (StringUtil.count(previousLine, CharPool.OPEN_PARENTHESIS) > 1) {
			int pos = trimmedPreviousLine.lastIndexOf(
				CharPool.OPEN_PARENTHESIS, trimmedPreviousLine.length() - 2);

			if ((pos > 0) &&
				Character.isLetterOrDigit(
					trimmedPreviousLine.charAt(pos -1 ))) {

				String filePart = trimmedPreviousLine.substring(pos + 1);

				if (!filePart.contains(StringPool.CLOSE_PARENTHESIS) &&
					!filePart.contains(StringPool.QUOTE)) {

					return getCombinedLinesContent(
						content, fileName, line, trimmedLine, lineLength,
						lineCount, previousLine, filePart, tabDiff, false,
						false, 0);
				}
			}
		}

		if ((trimmedLine.length() + previousLineLength) > _MAX_LINE_LENGTH) {
			return null;
		}

		if (line.endsWith(StringPool.COMMA)) {
			if (getLevel(trimmedLine) < 0) {
				return getCombinedLinesContent(
					content, fileName, line, trimmedLine, lineLength, lineCount,
					previousLine, null, tabDiff, false, false, 0);
			}
		}

		if (((line.endsWith(StringPool.OPEN_CURLY_BRACE) &&
			  !trimmedLine.startsWith("new ")) ||
			 line.endsWith(StringPool.CLOSE_PARENTHESIS)) &&
			(trimmedPreviousLine.startsWith("else ") ||
			 trimmedPreviousLine.startsWith("if ") ||
			 trimmedPreviousLine.startsWith("private ") ||
			 trimmedPreviousLine.startsWith("protected ") ||
			 trimmedPreviousLine.startsWith("public "))) {

			return getCombinedLinesContent(
				content, fileName, line, trimmedLine, lineLength, lineCount,
				previousLine, null, tabDiff, false, false, 0);
		}

		return null;
	}

	protected String getFormattedClassLine(String indent, String classLine) {
		while (classLine.contains(StringPool.TAB + StringPool.SPACE)) {
			classLine = StringUtil.replace(
				classLine, StringPool.TAB + StringPool.SPACE, StringPool.TAB);
		}

		String classSingleLine = StringUtil.replace(
			classLine.substring(1),
			new String[] {StringPool.TAB, StringPool.NEW_LINE},
			new String[] {StringPool.BLANK, StringPool.SPACE});

		classSingleLine = indent + classSingleLine;

		List<String> lines = new ArrayList<>();

		outerWhile:
		while (true) {
			if (getLineLength(classSingleLine) <= _MAX_LINE_LENGTH) {
				lines.add(classSingleLine);

				break;
			}

			String newIndent = indent;
			String newLine = classSingleLine;

			int x = -1;

			while (true) {
				int y = newLine.indexOf(" extends ", x + 1);

				if (y == -1) {
					x = newLine.indexOf(" implements ", x + 1);
				}
				else {
					x = y;
				}

				if (x == -1) {
					break;
				}

				String linePart = newLine.substring(0, x);

				if ((getLevel(linePart, "<", ">") == 0) &&
					(getLineLength(linePart) <= _MAX_LINE_LENGTH)) {

					if (lines.isEmpty()) {
						newIndent = newIndent + StringPool.TAB;
					}

					lines.add(linePart);

					newLine = newIndent + newLine.substring(x + 1);

					if (getLineLength(newLine) <= _MAX_LINE_LENGTH) {
						lines.add(newLine);

						break outerWhile;
					}

					x = -1;
				}
			}

			if (lines.isEmpty()) {
				return null;
			}

			x = newLine.length();

			while (true) {
				x = newLine.lastIndexOf(", ", x - 1);

				if (x == -1) {
					return null;
				}

				String linePart = newLine.substring(0, x + 1);

				if ((getLevel(linePart, "<", ">") == 0) &&
					(getLineLength(linePart) <= _MAX_LINE_LENGTH)) {

					lines.add(linePart);

					if (linePart.contains("\textends")) {
						newIndent = newIndent + "\t\t";
					}
					else if (linePart.contains("\timplements")) {
						newIndent = newIndent + "\t\t   ";
					}

					newLine = newIndent + newLine.substring(x + 2);

					if (getLineLength(newLine) <= _MAX_LINE_LENGTH) {
						lines.add(newLine);

						break outerWhile;
					}

					x = newLine.length();
				}
			}
		}

		String formattedClassLine = null;

		for (String line : lines) {
			if (formattedClassLine == null) {
				formattedClassLine = "\n" + line;
			}
			else {
				formattedClassLine = formattedClassLine + "\n" + line;
			}
		}

		return formattedClassLine;
	}

	protected int getIfClauseLineBreakPos(String line) {
		int x = line.lastIndexOf(" || ", _MAX_LINE_LENGTH - 3);
		int y = line.lastIndexOf(" && ", _MAX_LINE_LENGTH - 3);

		int z = Math.max(x, y);

		if (z != -1) {
			return z + 3;
		}

		if (!line.endsWith(" ||") && !line.endsWith(" &&") &&
			!line.endsWith(") {")) {

			return -1;
		}

		x = line.indexOf("= ");

		if (x != -1) {
			return x + 1;
		}

		x = line.indexOf("> ");

		if (x != -1) {
			return x + 1;
		}

		x = line.indexOf("< ");

		if (x != -1) {
			return x + 1;
		}

		for (x = _MAX_LINE_LENGTH + 1;;) {
			x = line.lastIndexOf(StringPool.COMMA_AND_SPACE, x - 1);

			if (x == -1) {
				break;
			}

			String linePart = line.substring(0, x);

			if (getLevel(linePart) == 0) {
				return x + 1;
			}
		}

		for (x = 0;;) {
			x = line.indexOf(CharPool.OPEN_PARENTHESIS, x + 1);

			if (x == -1) {
				break;
			}

			if (Character.isLetterOrDigit(line.charAt(x - 1))) {
				return x + 1;
			}
		}

		x = line.indexOf(CharPool.PERIOD);

		if (x != -1) {
			return x + 1;
		}

		return -1;
	}

	protected List<String> getImportedExceptionClassNames(
		JavaDocBuilder javaDocBuilder) {

		List<String> exceptionClassNames = new ArrayList<>();

		JavaSource javaSource = javaDocBuilder.getSources()[0];

		for (String importClassName : javaSource.getImports()) {
			if (importClassName.endsWith("Exception") &&
				!exceptionClassNames.contains(importClassName)) {

				exceptionClassNames.add(importClassName);
			}
		}

		return exceptionClassNames;
	}

	protected int getIncorrectLineBreakPos(String line, String previousLine) {
		for (int x = line.length();;) {
			int y = line.lastIndexOf(" || ", x - 1);
			int z = line.lastIndexOf(" && ", x - 1);

			x = Math.max(y, z);

			if (x == -1) {
				return x;
			}

			if (ToolsUtil.isInsideQuotes(line, x)) {
				continue;
			}

			if (Validator.isNotNull(previousLine) &&
				(getLevel(line.substring(0, x)) < 0)) {

				return x + 3;
			}

			if (!line.endsWith(" ||") && !line.endsWith(" &&")) {
				continue;
			}

			if (getLevel(line.substring(x)) > 0) {
				return x + 3;
			}
		}
	}

	protected String getModuleClassContent(String fullClassName)
		throws Exception {

		String classContent = _moduleFileContentsMap.get(fullClassName);

		if (classContent != null) {
			return classContent;
		}

		Map<String, String> moduleFileNamesMap = getModuleFileNamesMap();

		String moduleFileName = moduleFileNamesMap.get(fullClassName);

		if (moduleFileName == null) {
			_moduleFileContentsMap.put(fullClassName, StringPool.BLANK);

			return StringPool.BLANK;
		}

		File file = new File(moduleFileName);

		classContent = FileUtil.read(file);

		_moduleFileContentsMap.put(fullClassName, classContent);

		return classContent;
	}

	protected Map<String, String> getModuleFileNamesMap() throws Exception {
		if (_moduleFileNamesMap != null) {
			return _moduleFileNamesMap;
		}

		_moduleFileNamesMap = new HashMap<>();

		List<String> fileNames = new ArrayList<>();

		String moduleRootDirLocation = "modules/";

		for (int i = 0; i < 6; i++) {
			File file = new File(
				sourceFormatterArgs.getBaseDirName() + moduleRootDirLocation);

			if (file.exists()) {
				fileNames = getFileNames(
					sourceFormatterArgs.getBaseDirName() +
						moduleRootDirLocation,
					null, new String[0], getIncludes());

				break;
			}

			moduleRootDirLocation = "../" + moduleRootDirLocation;
		}

		for (String fileName : fileNames) {
			fileName = StringUtil.replace(
				fileName, StringPool.BACK_SLASH, StringPool.SLASH);

			String className = StringUtil.replace(
				fileName, StringPool.SLASH, StringPool.PERIOD);

			int pos = className.lastIndexOf(".com.liferay.");

			className = className.substring(pos + 1, fileName.length() - 5);

			_moduleFileNamesMap.put(className, fileName);
		}

		return _moduleFileNamesMap;
	}

	protected String getModuleServicePackagePath(String fileName) {
		String serviceDirLocation = fileName;

		while (true) {
			int pos = serviceDirLocation.lastIndexOf(StringPool.SLASH);

			if (pos == -1) {
				return StringPool.BLANK;
			}

			serviceDirLocation = serviceDirLocation.substring(0, pos + 1);

			File file = new File(serviceDirLocation + "service");

			if (file.exists()) {
				serviceDirLocation = serviceDirLocation + "service";

				break;
			}

			file = new File(serviceDirLocation + "liferay");

			if (file.exists()) {
				return StringPool.BLANK;
			}

			serviceDirLocation = StringUtil.replaceLast(
				serviceDirLocation, StringPool.SLASH, StringPool.BLANK);
		}

		serviceDirLocation = StringUtil.replace(
			serviceDirLocation, StringPool.SLASH, StringPool.PERIOD);

		int pos = serviceDirLocation.lastIndexOf(".com.");

		return serviceDirLocation.substring(pos + 1);
	}

	protected String getModuleSuperClassContent(
			String content, String className, String packagePath)
		throws Exception {

		Pattern pattern = Pattern.compile(
			" class " + className + "\\s+extends\\s+([\\w.]+) ");

		Matcher matcher = pattern.matcher(content);

		if (!matcher.find()) {
			return null;
		}

		String superClassName = matcher.group(1);

		if (superClassName.contains(StringPool.PERIOD)) {
			if (!superClassName.startsWith("com.liferay")) {
				return null;
			}

			return getModuleClassContent(superClassName);
		}

		String superClassPackagePath = packagePath;

		pattern = Pattern.compile("\nimport (.+?)\\." + superClassName + ";");

		matcher = pattern.matcher(content);

		if (matcher.find()) {
			superClassPackagePath = matcher.group(1);
		}

		if (!superClassPackagePath.startsWith("com.liferay")) {
			return null;
		}

		String superClassFullClassName =
			superClassPackagePath + StringPool.PERIOD + superClassName;

		return getModuleClassContent(superClassFullClassName);
	}

	protected Collection<String> getPluginJavaFiles() throws Exception {
		Collection<String> fileNames = new TreeSet<>();

		String[] excludes = new String[] {
			"**/model/*Clp.java", "**/model/impl/*BaseImpl.java",
			"**/model/impl/*Model.java", "**/model/impl/*ModelImpl.java",
			"**/service/**/service/*Service.java",
			"**/service/**/service/*ServiceClp.java",
			"**/service/**/service/*ServiceFactory.java",
			"**/service/**/service/*ServiceUtil.java",
			"**/service/**/service/*ServiceWrapper.java",
			"**/service/**/service/ClpSerializer.java",
			"**/service/**/service/messaging/*ClpMessageListener.java",
			"**/service/**/service/persistence/*Finder.java",
			"**/service/**/service/persistence/*Util.java",
			"**/service/base/*ServiceBaseImpl.java",
			"**/service/base/*ServiceClpInvoker.java",
			"**/service/http/*JSONSerializer.java",
			"**/service/http/*ServiceHttp.java",
			"**/service/http/*ServiceJSON.java",
			"**/service/http/*ServiceSoap.java", "**/tools/templates/**"
		};
		String[] includes = new String[] {"**/*.java"};

		fileNames.addAll(getFileNames(excludes, includes));

		return fileNames;
	}

	protected Collection<String> getPortalJavaFiles() throws Exception {
		Collection<String> fileNames = new TreeSet<>();

		String[] excludes = new String[] {
			"**/*_IW.java", "**/PropsValues.java", "**/counter/service/**",
			"**/jsp/*", "**/model/impl/*BaseImpl.java",
			"**/model/impl/*Model.java", "**/model/impl/*ModelImpl.java",
			"**/portal/service/**", "**/portal-client/**",
			"**/portal-web/test/**/*Test.java", "**/portlet/**/service/**",
			"**/test/*-generated/**", "**/source/formatter/**"
		};
		String[] includes = getIncludes();

		fileNames.addAll(getFileNames(excludes, includes));

		excludes = new String[] {
			"**/portal-client/**", "**/tools/ext_tmpl/**", "**/*_IW.java",
			"**/test/**/*PersistenceTest.java", "**/source/formatter/**"
		};
		includes = new String[] {
			"**/com/liferay/portal/kernel/service/ServiceContext*.java",
			"**/model/BaseModel.java", "**/model/impl/BaseModelImpl.java",
			"**/portal-test/**/portal/service/**/*.java",
			"**/portal-test-integration/**/portal/service/**/*.java",
			"**/service/Base*.java",
			"**/service/PersistedModelLocalService*.java",
			"**/service/http/*HttpTest.java", "**/service/http/*SoapTest.java",
			"**/service/http/TunnelUtil.java", "**/service/impl/*.java",
			"**/service/jms/*.java", "**/service/permission/*.java",
			"**/service/persistence/BasePersistence.java",
			"**/service/persistence/BatchSession*.java",
			"**/service/persistence/*FinderImpl.java",
			"**/service/persistence/*Query.java",
			"**/service/persistence/impl/*.java",
			"**/portal-impl/test/**/*.java", "**/util-bridges/**/*.java"
		};

		fileNames.addAll(getFileNames(excludes, includes));

		return fileNames;
	}

	protected String getTruncateLongLinesContent(
		String content, String line, String trimmedLine, int lineCount) {

		String indent = StringPool.BLANK;

		for (int i = 0; i < getLeadingTabCount(line); i++) {
			indent += StringPool.TAB;
		}

		if (line.endsWith(StringPool.OPEN_PARENTHESIS) ||
			line.endsWith(StringPool.SEMICOLON)) {

			int x = line.indexOf(" = ");

			if (x != -1) {
				String firstLine = line.substring(0, x + 2);

				if (firstLine.contains(StringPool.QUOTE)) {
					return null;
				}

				String secondLine =
					indent + StringPool.TAB + line.substring(x + 3);

				if (line.endsWith(StringPool.SEMICOLON)) {
					return StringUtil.replace(
						content, "\n" + line + "\n",
						"\n" + firstLine + "\n" + secondLine + "\n");
				}
				else if (Validator.isNotNull(getLine(content, lineCount + 1))) {
					return StringUtil.replace(
						content, "\n" + line + "\n",
						"\n" + firstLine + "\n" + secondLine + "\n" +
							StringPool.TAB);
				}
			}
		}

		if (line.endsWith(StringPool.CLOSE_PARENTHESIS) ||
			line.endsWith(StringPool.COMMA) ||
			line.endsWith(StringPool.OPEN_CURLY_BRACE) ||
			line.endsWith(StringPool.SEMICOLON)) {

			int x = 0;

			while (true) {
				x = line.indexOf(", ", x + 1);

				if (x == -1) {
					break;
				}

				if (isValidJavaParameter(line.substring(0, x))) {
					String firstLine = line.substring(0, x + 1);
					String secondLine = indent + line.substring(x + 2);

					return StringUtil.replace(
						content, "\n" + line + "\n",
						"\n" + firstLine + "\n" + secondLine + "\n");
				}
			}
		}

		if ((line.endsWith(StringPool.OPEN_CURLY_BRACE) ||
			 line.endsWith(StringPool.SEMICOLON)) &&
			(trimmedLine.startsWith("private ") ||
			 trimmedLine.startsWith("protected ") ||
			 trimmedLine.startsWith("public "))) {

			int x = line.indexOf(" throws ");

			if (x != -1) {
				String firstLine = line.substring(0, x);
				String secondLine =
					indent + StringPool.TAB + line.substring(x + 1);

				return StringUtil.replace(
					content, "\n" + line + "\n",
					"\n" + firstLine + "\n" + secondLine + "\n");
			}
		}

		if ((line.endsWith(StringPool.CLOSE_PARENTHESIS) ||
			 line.endsWith(StringPool.OPEN_CURLY_BRACE)) &&
			(trimmedLine.startsWith("private ") ||
			 trimmedLine.startsWith("protected ") ||
			 trimmedLine.startsWith("public "))) {

			int x = line.indexOf(CharPool.OPEN_PARENTHESIS);

			if ((x != -1) &&
				(line.charAt(x + 1) != CharPool.CLOSE_PARENTHESIS)) {

				String secondLineIndent = indent + StringPool.TAB;

				if (line.endsWith(StringPool.CLOSE_PARENTHESIS)) {
					secondLineIndent += StringPool.TAB;
				}

				String firstLine = line.substring(0, x + 1);
				String secondLine = secondLineIndent + line.substring(x + 1);

				return StringUtil.replace(
					content, "\n" + line + "\n",
					"\n" + firstLine + "\n" + secondLine + "\n");
			}
		}

		if (line.endsWith(StringPool.SEMICOLON)) {
			int x = line.indexOf(CharPool.OPEN_PARENTHESIS);

			if (x != -1) {
				char c = line.charAt(x - 1);

				if ((c != CharPool.SPACE) && (c != CharPool.TAB) &&
					(line.charAt(x + 1) != CharPool.CLOSE_PARENTHESIS)) {

					String firstLine = line.substring(0, x + 1);

					if (firstLine.contains(StringPool.QUOTE)) {
						return null;
					}

					String secondLine =
						indent + StringPool.TAB + line.substring(x + 1);

					return StringUtil.replace(
						content, "\n" + line + "\n",
						"\n" + firstLine + "\n" + secondLine + "\n");
				}
			}
			else {
				x = line.lastIndexOf(StringPool.SPACE);

				if (x != -1) {
					String firstLine = line.substring(0, x);
					String secondLine =
						indent + StringPool.TAB + line.substring(x + 1);

					if (getLineLength(secondLine) <= _MAX_LINE_LENGTH) {
						return StringUtil.replace(
							content, "\n" + line + "\n",
							"\n" + firstLine + "\n" + secondLine + "\n");
					}
				}
			}
		}

		if (line.contains(StringPool.TAB + "for (") && line.endsWith(" {")) {
			int x = line.indexOf(" : ");

			if (x != -1) {
				String firstLine = line.substring(0, x + 2);
				String secondLine =
					indent + StringPool.TAB + StringPool.TAB +
						line.substring(x + 3);

				return StringUtil.replace(
					content, "\n" + line + "\n",
					"\n" + firstLine + "\n" + secondLine + "\n\n");
			}
		}

		int i = getIfClauseLineBreakPos(line);

		if (i == -1) {
			return null;
		}

		String firstLine = line.substring(0, i);
		String secondLine = indent + line.substring(i);

		if (secondLine.endsWith(") {")) {
			return StringUtil.replace(
				content, "\n" + line + "\n",
				"\n" + firstLine + "\n" + secondLine + "\n\n");
		}

		return StringUtil.replace(
			content, "\n" + line + "\n",
			"\n" + firstLine + "\n" + secondLine + "\n");
	}

	protected boolean hasGeneratedTag(String content) {
		if ((content.contains("* @generated") || content.contains("$ANTLR")) &&
			!content.contains("hasGeneratedTag")) {

			return true;
		}
		else {
			return false;
		}
	}

	protected boolean isAnnotationParameter(String content, String line) {
		int x = -1;

		while (true) {
			x = line.indexOf(StringPool.COMMA_AND_SPACE, x + 1);

			if (x == -1) {
				break;
			}

			if (!ToolsUtil.isInsideQuotes(line, x)) {
				return false;
			}
		}

		Matcher matcher = _annotationPattern.matcher(content);

		while (matcher.find()) {
			String annotationParameters = matcher.group(3);

			if (annotationParameters.contains(line)) {
				return true;
			}
		}

		return false;
	}

	protected boolean isValidJavaParameter(String javaParameter) {
		if (javaParameter.contains(" implements ") ||
			javaParameter.contains(" throws ")) {

			return false;
		}

		int quoteCount = StringUtil.count(javaParameter, CharPool.QUOTE);

		if ((quoteCount % 2) == 1) {
			return false;
		}

		if ((getLevel(javaParameter, "(", ")") == 0) &&
			(getLevel(javaParameter, "<", ">") == 0) &&
			(getLevel(javaParameter, "{", "}") == 0)) {

			return true;
		}

		return false;
	}

	@Override
	protected void preFormat() {
		_addMissingDeprecationReleaseVersion = GetterUtil.getBoolean(
			getProperty("add.missing.deprecation.release.version"));
		_allowUseServiceUtilInServiceImpl = GetterUtil.getBoolean(
			getProperty("allow.use.service.util.in.service.impl"));
		_checkJavaFieldTypesExcludes = getPropertyList(
			"check.java.field.types.excludes");
		_checkTabsExcludes = getPropertyList("check.tabs.excludes");
		_diamondOperatorExcludes = getPropertyList("diamond.operator.excludes");
		_fitOnSingleLineExcludes = getPropertyList(
			"fit.on.single.line.excludes");
		_hibernateSQLQueryExcludes = getPropertyList(
			"hibernate.sql.query.excludes");
		_javaTermAccessLevelModifierExcludes = getPropertyList(
			"javaterm.access.level.modifier.excludes");
		_javaTermSortExcludes = getPropertyList("javaterm.sort.excludes");
		_lineLengthExcludes = getPropertyList("line.length.excludes");
		_proxyExcludes = getPropertyList("proxy.excludes");
		_secureDeserializationExcludes = getPropertyList(
			"secure.deserialization.excluded.files");
		_secureRandomExcludes = getPropertyList("secure.random.excludes");
		_secureXmlExcludes = getPropertyList("secure.xml.excludes");
		_staticLogVariableExcludes = getPropertyList("static.log.excludes");
		_testAnnotationsExcludes = getPropertyList("test.annotations.excludes");
		_upgradeServiceUtilExcludes = getPropertyList(
			"upgrade.service.util.excludes");
	}

	@Override
	protected void postFormat() throws Exception {
		checkBndInheritAnnotationOption();
	}

	protected void setBNDInheritRequiredValue(
			String fileName, boolean bndInheritRequired)
		throws Exception {

		Tuple bndFileLocationAndContentTuple =
			getBNDFileLocationAndContentTuple(fileName);

		String bndFileLocation =
			(String)bndFileLocationAndContentTuple.getObject(0);

		Tuple bndInheritTuple = _bndInheritRequiredTupleMap.get(
			bndFileLocation);

		if ((bndInheritTuple == null) || bndInheritRequired) {
			String bndContent =
				(String)bndFileLocationAndContentTuple.getObject(1);

			_bndInheritRequiredTupleMap.put(
				bndFileLocation, new Tuple(bndContent, bndInheritRequired));
		}
	}

	protected String sortExceptions(String line) {
		if (!line.endsWith(StringPool.OPEN_CURLY_BRACE) &&
			!line.endsWith(StringPool.SEMICOLON)) {

			return line;
		}

		int x = line.indexOf("throws ");

		if (x == -1) {
			return line;
		}

		String previousException = StringPool.BLANK;

		String[] exceptions = StringUtil.split(
			line.substring(x), CharPool.SPACE);

		for (int i = 1; i < exceptions.length; i++) {
			String exception = exceptions[i];

			if (exception.equals(StringPool.OPEN_CURLY_BRACE)) {
				break;
			}

			if (exception.endsWith(StringPool.COMMA) ||
				exception.endsWith(StringPool.SEMICOLON)) {

				exception = exception.substring(0, exception.length() - 1);
			}

			if (Validator.isNotNull(previousException) &&
				(previousException.compareToIgnoreCase(exception) > 0)) {

				line = StringUtil.replace(
					line, previousException + ", " + exception,
					exception + ", " + previousException);

				return sortExceptions(line);
			}

			previousException = exception;
		}

		return line;
	}

	private static final String[] _INCLUDES = new String[] {"**/*.java"};

	private static final int _MAX_LINE_LENGTH = 80;

	private static Pattern _annotationPattern = Pattern.compile(
		"(\t*)@(.+)\\(\n([\\s\\S]*?)\\)\n");

	private boolean _addMissingDeprecationReleaseVersion;
	private boolean _allowUseServiceUtilInServiceImpl;
	private Pattern _annotationMetaTypePattern = Pattern.compile(
		"\\s(name|description) = \"%");
	private Pattern _arrayPattern = Pattern.compile(
		"(\n\t*.* =) (new \\w*\\[\\] \\{)\n(\t*)(.+)\n\t*(\\};)\n");
	private Pattern _assertEqualsPattern = Pattern.compile(
		"Assert\\.assertEquals\\((.*?)\\);\n", Pattern.DOTALL);
	private Map<String, Tuple> _bndInheritRequiredTupleMap = new HashMap<>();
	private Pattern _catchExceptionPattern = Pattern.compile(
		"\n(\t+)catch \\((.+Exception) (.+)\\) \\{\n");
	private List<String> _checkJavaFieldTypesExcludes;
	private boolean _checkRegistryInTestClasses;
	private List<String> _checkTabsExcludes;
	private boolean _checkUnprocessedExceptions;
	private final Pattern _classPattern = Pattern.compile(
		"(\n(\t*)(private|protected|public) ((abstract|static) )*" +
			"(class|enum|interface) ([\\s\\S]*?) \\{)\n(\\s*)(\\S)");
	private Pattern _combinedLinesPattern1 = Pattern.compile(
		"\n(\t*).+(=|\\]) (\\{)\n");
	private Pattern _combinedLinesPattern2 = Pattern.compile(
		"\n(\t*)@.+(\\()\n");
	private Pattern _componentAnnotationPattern = Pattern.compile(
		"@Component(\n|\\([\\s\\S]*?\\)\n)");
	private List<String> _diamondOperatorExcludes;
	private Pattern _diamondOperatorPattern = Pattern.compile(
		"(return|=)\n?(\t+| )new ([A-Za-z]+)(\\s*)<(.+)>\\(\n*\t*.*\\);\n");
	private Pattern _fetchByPrimaryKeysMethodPattern = Pattern.compile(
		"@Override\n\tpublic Map<(.+)> fetchByPrimaryKeys\\(");
	private List<String> _fitOnSingleLineExcludes;
	private List<String> _hibernateSQLQueryExcludes;
	private Pattern _incorrectCloseCurlyBracePattern1 = Pattern.compile(
		"\n(.+)\n\n(\t+)}\n");
	private Pattern _incorrectCloseCurlyBracePattern2 = Pattern.compile(
		"(\t| )@?(class|enum|interface|new)\\s");
	private Pattern _incorrectLineBreakPattern1 = Pattern.compile(
		"\t(catch |else |finally |for |if |try |while ).*\\{\n\n\t+\\w");
	private Pattern _incorrectLineBreakPattern2 = Pattern.compile(
		"\\{\n\n\t*\\}");
	private Pattern _incorrectLineBreakPattern3 = Pattern.compile(
		", new .*\\(.*\\) \\{\n");
	private Pattern _incorrectLineBreakPattern4 = Pattern.compile(
		"\n(\t*)(.*\\) \\{)([\t ]*\\}\n)");
	private Pattern _incorrectLineBreakPattern5 = Pattern.compile(
		"\n(\t*).*\\}\n(\t*)\\);");
	private Pattern[] _javaSerializationVulnerabilityPatterns = new Pattern[] {
		Pattern.compile(
			".*(new [a-z\\.\\s]*ObjectInputStream).*", Pattern.DOTALL),
		Pattern.compile(
			".*(extends [a-z\\.\\s]*ObjectInputStream).*", Pattern.DOTALL)
	};
	private List<String> _javaTermAccessLevelModifierExcludes;
	private List<String> _javaTermSortExcludes;
	private List<String> _lineLengthExcludes;
	private Pattern _logLevelPattern = Pattern.compile(
		"\n(\t+)_log.(debug|error|info|trace|warn)\\(");
	private Pattern _logPattern = Pattern.compile(
		"\n\tprivate static final Log _log = LogFactoryUtil.getLog\\(\n*" +
			"\t*(.+)\\.class\\)");
	private Pattern _lowerCaseNumberOrPeriodPattern = Pattern.compile(
		"[a-z0-9.]");
	private Pattern _missingEmptyLinePattern1 = Pattern.compile(
		"(\t| = |return )new .*\\(.*\\) \\{\n\t+[^{\t]");
	private Pattern _missingEmptyLinePattern2 = Pattern.compile(
		"(\n\t*)(public|private|protected) [^;]+? \\{");
	private Map<String, String> _moduleFileContentsMap = new HashMap<>();
	private Map<String, String> _moduleFileNamesMap;
	private Pattern _processCallablePattern = Pattern.compile(
		"implements ProcessCallable\\b");
	private List<String> _proxyExcludes;
	private Pattern _redundantCommaPattern = Pattern.compile(",\n\t+\\}");
	private Pattern _redundantEmptyLines1 = Pattern.compile(
		"\n\npublic ((abstract|static) )*(class|enum|interface) ");
	private Pattern _redundantEmptyLines2 = Pattern.compile(
		" \\* @author .*\n \\*\\/\n\n");
	private Pattern _referenceMethodContentPattern = Pattern.compile(
		"^(\\w+) =\\s+\\w+;$");
	private Pattern _referenceMethodPattern = Pattern.compile(
		"\n\t@Reference([\\s\\S]*?)\\s+((protected|public) void (\\w+?))\\(" +
			"\\s*([ ,<>\\w]+)\\s+\\w+\\) \\{\\s+([\\s\\S]*?)\\s*?\n\t\\}\n");
	private Pattern _registryImportPattern = Pattern.compile(
		"\nimport (com\\.liferay\\.registry\\..+);");
	private List<String> _secureDeserializationExcludes;
	private List<String> _secureRandomExcludes;
	private List<String> _secureXmlExcludes;
	private Pattern _serviceUtilImportPattern = Pattern.compile(
		"\nimport ([A-Za-z1-9\\.]*)\\.([A-Za-z1-9]*ServiceUtil);");
	private Pattern _setVariablePattern = Pattern.compile(
		"\t[A-Z]\\w+ (\\w+) =\\s+((?!\\{\n).)*?;\n", Pattern.DOTALL);
	private Pattern _stagedModelTypesPattern = Pattern.compile(
		"StagedModelType\\(([a-zA-Z.]*(class|getClassName[\\(\\)]*))\\)");
	private List<String> _staticLogVariableExcludes;
	private List<String> _testAnnotationsExcludes;
	private Pattern _throwsSystemExceptionPattern = Pattern.compile(
		"(\n\t+.*)throws(.*) SystemException(.*)( \\{|;\n)");
	private List<String> _upgradeServiceUtilExcludes;

}