// JavaShell.java
//

package ijava.shell;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import ijava.*;
import ijava.shell.compiler.*;
import ijava.shell.extensions.*;
import ijava.shell.util.*;

/**
 * Provides the interactive shell or REPL functionality for Java.
 */
public final class JavaShell implements Evaluator {

  private final static String ERROR_TYPE_REDECLARED =
      "The value of the variable '%s' of type '%s' is no longer valid. " +
          "It appears the type of that variable has been redeclared to '%s'. " +
          "Please re-run the code to initialize the variable again.";

  private final HashMap<String, EvaluatorExtension> _extensions;

  private final HashSet<String> _imports;
  private final HashSet<String> _staticImports;
  private final HashSet<String> _packages;
  private final HashMap<String, byte[]> _types;

  private final JavaShellState _state;

  private ClassLoader _classLoader;
  private String _cachedImports;

  /**
   * Initializes an instance of an JavaShell.
   */
  public JavaShell() {
    _extensions = new HashMap<String, EvaluatorExtension>();

    _imports = new HashSet<String>();
    _staticImports = new HashSet<String>();
    _packages = new HashSet<String>();
    _types = new HashMap<String, byte[]>();

    // The state resulting from executing code
    _state = new JavaShellState();

    // Default the class loader to the system one initially.
    _classLoader = ClassLoader.getSystemClassLoader();

    // Import a few packages by default
    addImport("java.io.*", /* staticImport */ false);
    addImport("java.util.*", /* staticImport */ false);
    addImport("java.net.*", /* staticImport */ false);

    // Register a few extensions by default
    registerExtension("import", new JavaExtensions.ImportExtension());
  }

  /**
   * The set of imports declared in the shell.
   * @return the list of imports.
   */
  public String getImports() {
    if (_cachedImports == null) {
      StringBuilder sb = new StringBuilder();

      for (String s : _imports) {
        sb.append(String.format("import %s;", s));
      }
      for (String s : _staticImports) {
        sb.append(String.format("import static %s;", s));
      }

      _cachedImports = sb.toString();
    }

    return _cachedImports;
  }

  /**
   * Gets the current set of variables declared in the shell.
   * @return a set of name/value pairs.
   */
  public JavaShellState getState() {
    return _state;
  }

  /**
   * Adds a package to be imported for subsequent compilations.
   * @param importName the package or type to be imported.
   * @param staticImport whether the import should be a static import of a type.
   */
  public void addImport(String importName, boolean staticImport) {
    if (staticImport) {
      _staticImports.add(importName);
    }
    else {
      _imports.add(importName);
    }

    _cachedImports = null;
  }

  /**
   * Invokes an extension for the specified evaluation.
   * @param data the evaluation text.
   */
  private Object invokeExtension(String data) throws Exception {
    ExtensionParser parser = new ExtensionParser();
    Extension extensionData = parser.parse(data);

    if (extensionData == null) {
      throw new EvaluationError("Invalid syntax.");
    }

    String name = extensionData.getName();
    EvaluatorExtension extension = _extensions.get(name);
    if (extension == null) {
      throw new EvaluationError("Invalid syntax. Unknown identifier '" + name + "'");
    }

    return extension.evaluate(this, extensionData.getDeclaration(), extensionData.getContent());
  }

  /**
   * Process the results of compiling a set of class members or a code block, i.e. restore old
   * shell state, execute new code, and then update shell state with resulting updates.
   * @param id the ID to use to generate unique names.
   * @param snippet the compiled snippet.
   * @return the result of a code block execution, or null for class members execution or if there
   *         is an error.
   */
  private Object processCode(int id, Snippet snippet) throws Exception {
    SnippetCompilation compilation = snippet.getCompilation();
    ClassLoader classLoader = new CodeBlockClassLoader(_classLoader, id, compilation.getTypes());

    Class<?> snippetClass = classLoader.loadClass(snippet.getClassName());
    Object instance = snippetClass.newInstance();

    // Initialize the callable code instance with any current state
    boolean staleState = false;
    for (String variable: _state.getNames()) {
      Field field = snippetClass.getDeclaredField(variable);
      Object value = _state.getValue(variable);

      try {
        field.set(instance, value);
      }
      catch (IllegalArgumentException e) {
        _state.undeclareField(variable);
        staleState = true;

        String error = String.format(JavaShell.ERROR_TYPE_REDECLARED,
                                     variable,
                                     value.getClass().toString(),
                                     field.getClass().toString());
        System.err.println(error);
      }
    }

    if (staleState) {
      // Old state is stale, and the new instance was not fully initialized. So simply
      // bail out, rather than run with un-predictable results.
      return null;
    }

    // Execute the code
    Object result = ((Callable<?>)instance).call();

    if (snippet.getType() == SnippetType.ClassMembers) {
      // If the snippet represented a set of class members, then add any declared fields
      // to be tracked in state

      for (SnippetCodeMember member: snippet.getClassMembers()) {
        if (member.isField()) {
          _state.declareField(member.getName(), member.getType());
        }
        else {
          _state.declareMethod(member.getName(), member.getCode());
        }
      }

      // The result of execution is the instance to be used to retrieve updated state.
      // This is because the rewriter puts new class members declared in a nested class
      // that is instantiated and returned when call() is invoked.
      instance = result;
    }

    // Now extract any new/updated state to be tracked for use in future evaluations.
    Class<?> instanceClass = instance.getClass();
    for (String name: _state.getNames()) {
      try {
        Field field = instanceClass.getDeclaredField(name);
        field.setAccessible(true);

        _state.setValue(name, field.get(instance));
      }
      catch (NoSuchFieldException e) {
        // Ignore. This particular field was not declared or updated in the case of
        // a set of class members.
      }
    }

    if (snippet.getType() == SnippetType.ClassMembers) {
      // For class members, the result is simply a shim class containing the newly defined
      // members, i.e. not meaningful to return out of the shell.
      return null;
    }

    return result;
  }

  /**
   * Process the the results of compiling a compilation unit. This involves two things:
   * - Recording any packages created in the process.
   * - Stashing (or optionally updating) the byte code for types defined, and saving a reference
   *   to the new class loader created to enable loading those types.
   * @param id the ID to use to generate unique names.
   * @param snippet the compiled snippet.
   */
  private void processCompilationUnit(int id, Snippet snippet) {
    SnippetCompilation compilation = snippet.getCompilation();

    for (String packageName : compilation.getPackages()) {
      _packages.add(packageName);
    }

    HashSet<String> newNames = new HashSet<String>();
    for (Map.Entry<String, byte[]> typeEntry : compilation.getTypes().entrySet()) {
      String name = typeEntry.getKey();
      byte[] bytes = typeEntry.getValue();

      byte[] existingBytes = _types.get(name);
      if ((existingBytes != null) && Arrays.equals(existingBytes, bytes)) {
        // Same name, same byte code ... likely the user simply re-executed the same code.
        // Ignore the new class in favor of keeping the old class identity, and increase chances
        // that existing data instances of that class (should they exist) remain valid.

        continue;
      }

      _types.put(name, bytes);
      newNames.add(name);
    }

    if (newNames.size() != 0) {
      // Create a new class loader parented to the current one for the newly defined classes
      _classLoader = new ShellClassLoader(_classLoader, id, newNames);
    }
  }

  /**
   * Registers an extension so it may be invoked.
   * @param name the name of the extension used in invoking it.
   * @param extension the extension to be registered.
   */
  public void registerExtension(String name, EvaluatorExtension extension) {
    _extensions.put(name, extension);
  }

  /**
   * {@link Evaluator}
   */
  @Override
  public Object evaluate(String data, int evaluationID) throws Exception {
    if (data.startsWith("%")) {
      return invokeExtension(data);
    }

    Snippet snippet = null;
    try {
      SnippetParser parser = new SnippetParser();
      snippet = parser.parse(data, evaluationID);
    }
    catch (SnippetException e) {
      throw new EvaluationError(e.getMessage(), e);
    }

    JavaRewriter rewriter = new JavaRewriter(this);
    snippet.setRewrittenCode(rewriter.rewrite(snippet));

    SnippetCompiler compiler = new SnippetCompiler(_packages, _types);
    SnippetCompilation compilation = compiler.compile(snippet);

    if (!compilation.hasErrors()) {
      snippet.setCompilation(compilation);

      Object result = null;
      if (snippet.getType() == SnippetType.CompilationUnit) {
        processCompilationUnit(evaluationID, snippet);
      }
      else {
        result = processCode(evaluationID, snippet);
      }

      return result;
    }
    else {
      // Raise an error for compilation errors
      StringBuilder errorBuilder = new StringBuilder();
      for (String error : compilation.getErrors()) {
        errorBuilder.append(error);
        errorBuilder.append("\n");
      }

      throw new EvaluationError(errorBuilder.toString());
    }
  }


  /**
   * A class loader that holds on to classes declared within the shell.
   */
  private final class ShellClassLoader extends ByteCodeClassLoader {

    private final HashSet<String> _names;

    /**
     * Initializes an instance of a ShellClassLoader.
     * @param parentClassLoader the parent class loader to chain with.
     * @param id the ID of this class loader.
     * @param names the list of names that should be resolved with this class loader.
     */
    public ShellClassLoader(ClassLoader parentClassLoader, int id, HashSet<String> names) {
      super(parentClassLoader, id);
      _names = names;
    }

    @Override
    protected byte[] getByteCode(String name) {
      if (_names.contains(name)) {
        return _types.get(name);
      }

      return null;
    }
  }


  /**
   * A class loader that allows loading classes generated during compilation from code blocks
   * entered into the shell, while that code is being executed.
   */
  private final class CodeBlockClassLoader extends ByteCodeClassLoader {

    private final Map<String, byte[]> _types;

    /**
     * Initializes an instance of a CodeBlockClassLoader.
     * @param parentClassLoader the parent class loader to chain with.
     * @param id the ID of this class loader.
     * @param types the set of byte code buffers for types keyed by class names.
     */
    public CodeBlockClassLoader(ClassLoader parentClassLoader, int id,
                                Map<String, byte[]> types) {
      super(parentClassLoader, id);
      _types = types;
    }

    @Override
    protected byte[] getByteCode(String name) {
      return _types.get(name);
    }
  }
}