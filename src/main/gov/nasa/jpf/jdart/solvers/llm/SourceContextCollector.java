package gov.nasa.jpf.jdart.solvers.llm;

import com.google.gson.JsonObject;
import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.jdart.ConcolicMethodExplorer;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.util.Source;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects source code context for LLM-based constraint solving.
 * 
 * This collector extracts:
 * - Current method being analyzed (full source code)
 * - Related class source code
 * - Line number information
 * 
 * The source code is extracted using JPF's Source API and provided
 * to the LLM service to help understand the constraints better.
 * 
 * @author JDart Team
 */
public class SourceContextCollector {

	private static final Logger logger = Logger.getLogger(SourceContextCollector.class.getName());

	// Cache to avoid re-reading source files
	private static final Map<String, String> sourceCache = new HashMap<>();

	// Pattern to capture JVM descriptor tokens like LAnimal; or Lcom/foo/Bar;
	private static final Pattern JVM_TYPE_PATTERN = Pattern.compile("L[\\w/$]+;");

	/**
	 * Configuration for source context collection.
	 */
	public static class Config {
		/** Number of context lines to include before/after method */
		public int contextLines = 5;

		/** Whether to include full class source */
		public boolean includeFullClass = false;

		/** Whether to include line numbers in output */
		public boolean includeLineNumbers = true;

		/** Maximum characters for method source (to avoid token overflow) */
		public int maxMethodSourceLength = 3000;

		/** Maximum characters for class source */
		public int maxClassSourceLength = 8000;

		/** Only include constraint-relevant class definitions */
		public boolean onlyRelevantClasses = true;

		/** Maximum characters for related class source */
		public int maxRelatedClassLength = 2000;
	}

	private final Config config;

	/**
	 * Creates a SourceContextCollector with the given configuration.
	 * 
	 * @param config Configuration settings
	 */
	public SourceContextCollector(Config config) {
		this.config = config;
	}

	/**
	 * Creates a SourceContextCollector with default configuration.
	 * 
	 * @return A new collector with default settings
	 */
	public static SourceContextCollector createDefault() {
		return new SourceContextCollector(new Config());
	}

	/**
	 * Collects source code context from the current execution, favoring the
	 * concolic target method when available.
	 * 
	 * @param ti                       current thread info
	 * @param analysis                 current concolic analysis (may be null)
	 * @param hlExpressions            high-level constraints (for type mining, may
	 *                                 be null)
	 * @param parameterTypeConstraints parameter static types (may be null)
	 * @return JsonObject containing source context, or null if unavailable
	 */
	public JsonObject collectSourceContext(
			ThreadInfo ti, ConcolicMethodExplorer analysis,
			List<Expression<Boolean>> hlExpressions,
			Map<String, String> parameterTypeConstraints) {
		if (ti == null) {
			logger.fine("ThreadInfo is null, cannot collect source context");
			return null;
		}

		try {
			MethodInfo methodInfo = null;
			ClassInfo classInfo = null;

			// Prefer the currently analyzed method if available
			if (analysis != null) {
				try {
					// Access methodInfo through reflection since it's private
					java.lang.reflect.Field methodInfoField = analysis.getClass().getDeclaredField("methodInfo");
					methodInfoField.setAccessible(true);
					methodInfo = (MethodInfo) methodInfoField.get(analysis);
					if (methodInfo != null) {
						logger.info("Using concolic target method from analysis: " + methodInfo.getFullName());
					}
				} catch (Exception e) {
					logger.warning("Failed to get method from analysis via reflection: " + e.getMessage());
				}
			}

			// Fallback to stack frame only if reflection failed
			if (methodInfo == null) {
				gov.nasa.jpf.vm.StackFrame frame = ti.getTopFrame();
				if (frame != null) {
					methodInfo = frame.getMethodInfo();
					logger.info("Fallback to stack frame method: "
							+ (methodInfo != null ? methodInfo.getFullName() : "null"));
				}
			}

			if (methodInfo == null) {
				logger.fine("MethodInfo is null");
				return null;
			}

			classInfo = methodInfo.getClassInfo();

			JsonObject sourceContext = new JsonObject();

			// Add basic method information
			sourceContext.addProperty("method_name", methodInfo.getName());
			sourceContext.addProperty("method_signature", methodInfo.getSignature());
			sourceContext.addProperty("method_full_name", methodInfo.getFullName());

			// Get class information
			if (classInfo != null) {
				sourceContext.addProperty("class_name", classInfo.getName());
				sourceContext.addProperty("class_simple_name", classInfo.getSimpleName());
			}

			// Extract method source code
			String methodSource = extractMethodSource(methodInfo);
			if (methodSource != null && !methodSource.isEmpty()) {
				sourceContext.addProperty("method_source", methodSource);
			}

			// Extract class source code if enabled
			if (config.includeFullClass && classInfo != null) {
				String classSource = extractClassSource(classInfo);
				if (classSource != null && !classSource.isEmpty()) {
					sourceContext.addProperty("class_source", classSource);
				}
			}

			// Add related class sources based on constraints and parameter types
			JsonObject related = collectRelatedClassSources(classInfo, hlExpressions, parameterTypeConstraints);
			if (related != null && related.entrySet().size() > 0) {
				sourceContext.add("related_classes", related);
			}

			// Add line number information
			if (config.includeLineNumbers) {
				gov.nasa.jpf.vm.Instruction[] instructions = methodInfo.getInstructions();
				if (instructions != null && instructions.length > 0) {
					JsonObject lineInfo = new JsonObject();
					int startLine = methodInfo.getLineNumber(instructions[0]);
					int endLine = methodInfo.getLineNumber(instructions[instructions.length - 1]);
					lineInfo.addProperty("method_start", startLine);
					lineInfo.addProperty("method_end", endLine);
					sourceContext.add("line_numbers", lineInfo);
				}
			}

			// Add source file information
			if (classInfo != null) {
				sourceContext.addProperty("source_file", classInfo.getSourceFileName());
			}

			return sourceContext;

		} catch (Exception e) {
			logger.warning("Failed to collect source context: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Extracts the source code for a specific method.
	 * 
	 * @param methodInfo Method to extract source from
	 * @return Method source code, or null if unavailable
	 */
	private String extractMethodSource(MethodInfo methodInfo) {
		if (methodInfo == null) {
			return null;
		}

		try {
			ClassInfo classInfo = methodInfo.getClassInfo();
			if (classInfo == null) {
				return null;
			}

			Source source = resolveSource(classInfo);
			if (source == null) {
				logger.fine("Source not available for class: " + classInfo.getName());
				return null;
			}

			// Get method line range
			gov.nasa.jpf.vm.Instruction[] instructions = methodInfo.getInstructions();
			if (instructions == null || instructions.length == 0) {
				logger.fine("No instructions available for method: " + methodInfo.getName());
				return null;
			}

			int startLine = methodInfo.getLineNumber(instructions[0]);
			int endLine = methodInfo.getLineNumber(instructions[instructions.length - 1]);

			if (startLine <= 0 || endLine <= 0) {
				logger.fine("Invalid line numbers for method: " + methodInfo.getName());
				return null;
			}

			// Include context lines
			int actualStart = Math.max(1, startLine - config.contextLines);
			int actualEnd = Math.min(source.getLineCount(), endLine + config.contextLines);

			StringBuilder methodSource = new StringBuilder();
			for (int i = actualStart; i <= actualEnd; i++) {
				String line = source.getLine(i);
				if (line != null) {
					if (config.includeLineNumbers) {
						methodSource.append(String.format("%4d: %s\n", i, line));
					} else {
						methodSource.append(line).append("\n");
					}
				}
			}

			String result = methodSource.toString();

			// Truncate if too long
			if (result.length() > config.maxMethodSourceLength) {
				result = result.substring(0, config.maxMethodSourceLength) + "\n... (truncated)";
				logger.fine("Method source truncated to " + config.maxMethodSourceLength + " characters");
			}

			return result;

		} catch (Exception e) {
			logger.warning("Failed to extract method source: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Extracts the complete source code for a class.
	 * 
	 * @param classInfo Class to extract source from
	 * @return Class source code, or null if unavailable
	 */
	private String extractClassSource(ClassInfo classInfo) {
		if (classInfo == null) {
			return null;
		}

		// Check cache first
		String className = classInfo.getName();
		if (sourceCache.containsKey(className)) {
			return sourceCache.get(className);
		}

		try {
			Source source = resolveSource(classInfo);
			if (source == null) {
				logger.fine("Source not available for class: " + className);
				return null;
			}

			StringBuilder classSource = new StringBuilder();
			int lineCount = source.getLineCount();

			for (int i = 1; i <= lineCount; i++) {
				String line = source.getLine(i);
				if (line != null) {
					if (config.includeLineNumbers) {
						classSource.append(String.format("%4d: %s\n", i, line));
					} else {
						classSource.append(line).append("\n");
					}
				}
			}

			String result = classSource.toString();

			// Truncate if too long
			if (result.length() > config.maxClassSourceLength) {
				result = result.substring(0, config.maxClassSourceLength) + "\n... (truncated)";
				logger.fine("Class source truncated to " + config.maxClassSourceLength + " characters");
			}

			// Cache the result
			sourceCache.put(className, result);

			return result;

		} catch (Exception e) {
			logger.warning("Failed to extract class source: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Clears the source code cache.
	 */
	public static void clearCache() {
		sourceCache.clear();
	}

	/**
	 * Resolve Source for a given ClassInfo with fallbacks.
	 */
	private Source resolveSource(ClassInfo classInfo) {
		if (classInfo == null) {
			return null;
		}

		// Try direct lookup first
		Source source = classInfo.getSource();
		if (source != null) {
			return source;
		}

		// Fallback: try by simple source file name (requires sourcepath configured)
		String fileName = classInfo.getSourceFileName();
		if (fileName != null) {
			source = Source.getSource(fileName);
			if (source != null) {
				return source;
			}
		}

		// Fallback: try package-qualified path (pkg/Clazz.java)
		if (fileName != null) {
			String pkgPath = classInfo.getName().replace('.', '/');
			int idx = pkgPath.lastIndexOf('/');
			if (idx >= 0) {
				String qualified = pkgPath.substring(0, idx + 1) + fileName;
				source = Source.getSource(qualified);
				if (source != null) {
					return source;
				}
			}
		}

		logger.fine("Failed to resolve source for class: " + classInfo.getName());
		return null;
	}

	/**
	 * Collect source snippets for classes referenced by constraints or parameter
	 * types.
	 */
	private JsonObject collectRelatedClassSources(ClassInfo currentClass,
			List<Expression<Boolean>> hlExpressions,
			Map<String, String> parameterTypeConstraints) {
		Set<String> typeTokens = new HashSet<>();

		// System.out.println("\n\n");

		if (hlExpressions != null) {
			for (Expression<Boolean> expr : hlExpressions) {
				if (expr == null)
					continue;
				String s = expr.toString();

				// System.out.println("HL Expression: " + s);

				Matcher m = JVM_TYPE_PATTERN.matcher(s);
				while (m.find()) {
					// System.out.println("  Found type token: " + m.group());
					typeTokens.add(m.group());
				}

			}
		}

		if (parameterTypeConstraints != null) {
			for (String declared : parameterTypeConstraints.values()) {
				if (declared != null && !declared.isEmpty()) {
					typeTokens.add(toJvmDescriptor(declared));
				}
			}
		}

		if (typeTokens.isEmpty()) {
			return null;
		}

		JsonObject related = new JsonObject();
		for (String jvmType : typeTokens) {
			String className = jvmToClassName(jvmType);
			if (className == null || className.isEmpty()) {
				continue;
			}

			// Avoid duplicating the current class source
			if (currentClass != null && className.equals(currentClass.getName())) {
				continue;
			}

    //   System.out.println("Collecting related class source for: " + className);

			String source = resolveClassSourceByName(className);
    //   System.out.println("  Resolved source length: " + (source != null ? source.length() : "null"));

			if (source != null && !source.isEmpty()) {
				if (source.length() > config.maxRelatedClassLength) {
					source = source.substring(0, config.maxRelatedClassLength) + "\n... (truncated)";
				}
        // System.out.println("  Collected source (" + source.length() + " chars)");
				related.addProperty(className, source);
			}
		}

		// System.out.println("\n\n");

		return related;
	}

	/**
	 * Convert JVM descriptor (e.g., Lcom/foo/Bar;) to class name (com.foo.Bar).
	 */
	private String jvmToClassName(String jvmType) {
		if (jvmType == null || jvmType.length() < 3) {
			return null;
		}
		String t = jvmType;
		if (t.startsWith("L") && t.endsWith(";")) {
			t = t.substring(1, t.length() - 1);
		}
		return t.replace('/', '.');
	}

	/**
	 * Convert dotted class name to JVM descriptor for type mining convenience.
	 */
	private String toJvmDescriptor(String className) {
		if (className == null || className.isEmpty()) {
			return "";
		}
		if (className.startsWith("L") && className.endsWith(";")) {
			return className;
		}
		return "L" + className.replace('.', '/') + ";";
	}

	/**
	 * Resolve a class source by class name using Source lookups.
	 * Handles both standalone files and classes defined in the same file.
	 */
	private String resolveClassSourceByName(String className) {
		// Try cache first
		if (sourceCache.containsKey(className)) {
			return sourceCache.get(className);
		}

		// Try to load the class through JPF's ClassLoaderInfo
		try {
			gov.nasa.jpf.vm.ClassLoaderInfo sysCl = gov.nasa.jpf.vm.ClassLoaderInfo.getCurrentSystemClassLoader();
			if (sysCl != null) {
				ClassInfo classInfo = sysCl.getResolvedClassInfo(className);
				if (classInfo != null) {
					// System.out.println("  Found ClassInfo for: " + className + " in file: " + classInfo.getSourceFileName());
					Source src = resolveSource(classInfo);
					if (src != null) {
						String rendered = extractClassDefinition(src, className);
						if (rendered != null) {
							sourceCache.put(className, rendered);
							return rendered;
						}
					}
				}
			}
		} catch (Exception e) {
			logger.fine("Failed to resolve class via ClassLoader: " + e.getMessage());
		}

		// Fallback: try direct file lookup
		String simpleName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

		String[] candidates = new String[] {
				simpleName + ".java",
				className.replace('.', '/') + ".java"
		};

		for (String candidate : candidates) {
			Source src = Source.getSource(candidate);
			if (src != null) {
				String rendered = extractClassDefinition(src, className);
				if (rendered != null) {
					sourceCache.put(className, rendered);
					return rendered;
				}
			}
		}

		return null;
	}

	/**
	 * Extract just the class definition from source, limiting to 200 lines max.
	 * Finds the class declaration and extracts until the closing brace.
	 */
	private String extractClassDefinition(Source src, String className) {
		if (src == null) {
			return null;
		}

		String simpleName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
		int lineCount = src.getLineCount();
		
		// Find the class definition line
		int classStartLine = -1;
		for (int i = 1; i <= lineCount; i++) {
			String line = src.getLine(i);
			if (line != null && line.contains("class " + simpleName)) {
				// Simple heuristic: check if this looks like the class declaration
				if (line.matches(".*\\bclass\\s+" + simpleName + "\\b.*")) {
					classStartLine = i;
					break;
				}
			}
		}

		if (classStartLine == -1) {
			return null;
		}

		// Extract class definition by matching braces
		StringBuilder result = new StringBuilder();
		int braceCount = 0;
		boolean foundOpeningBrace = false;
		int lineLimit = Math.min(classStartLine + 199, lineCount);

		for (int i = classStartLine; i <= lineLimit; i++) {
			String line = src.getLine(i);
			if (line != null) {
				result.append(String.format("%4d: %s\n", i, line));

				// Count braces to find end of class
				for (char c : line.toCharArray()) {
					if (c == '{') {
						braceCount++;
						foundOpeningBrace = true;
					} else if (c == '}') {
						braceCount--;
					}
				}

				// If we found the opening brace and all braces are closed, we're done
				if (foundOpeningBrace && braceCount == 0) {
					break;
				}
			}
		}

		String output = result.toString();
		if (output.isEmpty()) {
			return null;
		}

		// Cap at 200 lines of output
		String[] lines = output.split("\n");
		if (lines.length > 200) {
			StringBuilder limited = new StringBuilder();
			for (int i = 0; i < 200; i++) {
				limited.append(lines[i]).append("\n");
			}
			limited.append("... (truncated)");
			return limited.toString();
		}

		return output;
	}

}
