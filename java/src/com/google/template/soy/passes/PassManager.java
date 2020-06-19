/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.template.soy.passes.CompilerFilePassToFileSetPassShim.filePassAsFileSetPass;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.css.CssRegistry;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.types.SoyTypeRegistry;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configures all compiler passes.
 *
 * <p>This arranges all compiler passes into four phases.
 *
 * <ul>
 *   <li>The single file passes. This includes AST rewriting passes such as {@link
 *       ResolveExpressionTypesPass} and {@link RewriteGenderMsgsPass} and other kinds of validation
 *       that doesn't require information about the full file set.
 *   <li>Cross template checking passes. This includes AST validation passes like the {@link
 *       CheckTemplateVisibilityPass}. Passes should run here if they need to check the
 *       relationships between templates.
 *   <li>The autoescaper. This runs in its own special phase because it can do special things like
 *       create synthetic templates and add them to the tree.
 *   <li>Simplification passes. This includes tree simplification passes like the optimizer. These
 *       should run last so that they can simplify code generated by any earlier pass.
 * </ul>
 *
 * <p>The reason things have been divided in this way is partially to create consistency and also to
 * enable other compiler features. For example, for in process (server side) compilation we can
 * cache the results of the single file passes to speed up edit-refresh flows. Also, theoretically,
 * we could run the single file passes for each file in parallel.
 *
 * <p>A note on ordering. There is no real structure to the ordering of the passes beyond what is
 * documented in comments. Many passes do rely on running before/after a different pass (e.g. {@link
 * ResolveExpressionTypesPass} needs to run after {@link ResolveNamesPass}), but there isn't any
 * dependency system in place.
 */
public final class PassManager {

  /**
   * Pass continuation rules.
   *
   * <p>These rules are used when running compile passes. You can stop compilation either before or
   * after a pass. By default, compilation continues after each pass without stopping.
   */
  public enum PassContinuationRule {
    STOP_BEFORE_PASS,
    STOP_AFTER_PASS,
  }

  @VisibleForTesting final ImmutableList<CompilerFilePass> singleFilePasses;
  @VisibleForTesting final ImmutableList<CompilerFileSetPass> partialTemplateRegistryPasses;
  @VisibleForTesting final ImmutableList<CompilerFileSetPass> crossTemplateCheckingPasses;

  private PassManager(
      ImmutableList<CompilerFilePass> singleFilePasses,
      ImmutableList<CompilerFileSetPass> partialTemplateRegistryPasses,
      ImmutableList<CompilerFileSetPass> crossTemplateCheckingPasses) {
    this.singleFilePasses = singleFilePasses;
    this.partialTemplateRegistryPasses = partialTemplateRegistryPasses;
    this.crossTemplateCheckingPasses = crossTemplateCheckingPasses;
    checkOrdering();
  }

  public void runSingleFilePasses(SoyFileNode file, IdGenerator nodeIdGen) {
    for (CompilerFilePass pass : singleFilePasses) {
      pass.run(file, nodeIdGen);
    }
  }

  /**
   * Runs fileset passes that only require partial template registries that only contain
   * dependencies.
   */
  public void runPartialTemplateRegistryPasses(
      SoyFileSetNode soyTree, TemplateRegistry templateRegistry) {
    ImmutableList<SoyFileNode> sourceFiles = ImmutableList.copyOf(soyTree.getChildren());
    IdGenerator idGenerator = soyTree.getNodeIdGenerator();
    for (CompilerFileSetPass pass : partialTemplateRegistryPasses) {
      CompilerFileSetPass.Result result = pass.run(sourceFiles, idGenerator, templateRegistry);
      if (result == CompilerFileSetPass.Result.STOP) {
        break;
      }
    }
  }

  /**
   * Runs all the fileset passes including the autoescaper and optimization passes if configured.
   */
  public void runWholeFilesetPasses(SoyFileSetNode soyTree, TemplateRegistry templateRegistry) {
    ImmutableList<SoyFileNode> sourceFiles = ImmutableList.copyOf(soyTree.getChildren());
    IdGenerator idGenerator = soyTree.getNodeIdGenerator();
    for (CompilerFileSetPass pass : crossTemplateCheckingPasses) {
      CompilerFileSetPass.Result result = pass.run(sourceFiles, idGenerator, templateRegistry);
      if (result == CompilerFileSetPass.Result.STOP) {
        break;
      }
    }
  }

  /** Enforces that the current set of passes doesn't violate any annotated ordering constraints. */
  private void checkOrdering() {
    Set<Class<? extends CompilerPass>> executed = new LinkedHashSet<>();
    for (CompilerPass pass :
        Iterables.concat(
            singleFilePasses, partialTemplateRegistryPasses, crossTemplateCheckingPasses)) {
      prepareToRun(executed, pass);
    }
  }

  private static void prepareToRun(Set<Class<? extends CompilerPass>> executed, CompilerPass pass) {
    ImmutableList<Class<? extends CompilerPass>> shouldHaveAlreadyRun = pass.runAfter();
    if (!executed.containsAll(shouldHaveAlreadyRun)) {
      throw new IllegalStateException(
          "Attempted to executed pass "
              + pass.name()
              + " but its dependencies ("
              + shouldHaveAlreadyRun.stream()
                  .filter(dep -> !executed.contains(dep))
                  .map(Class::getSimpleName)
                  .collect(joining(", "))
              + ") haven't run yet.\n Passes executed so far: "
              + executed.stream().map(Class::getSimpleName).collect(joining(", ")));
    }
    ImmutableList<Class<? extends CompilerPass>> shouldNotHaveAlreadyRun = pass.runBefore();
    if (!Collections.disjoint(executed, shouldNotHaveAlreadyRun)) {
      throw new IllegalStateException(
          "Attempted to executed pass "
              + pass.name()
              + " but it should always run before ("
              + shouldHaveAlreadyRun.stream()
                  .filter(dep -> !executed.contains(dep))
                  .map(Class::getSimpleName)
                  .collect(joining(", "))
              + ") haven't run yet.\n Passes executed so far: "
              + executed.stream().map(Class::getSimpleName).collect(joining(", ")));
    }
    executed.add(getPassClass(pass));
  }

  /** A builder for configuring the pass manager. */
  public static final class Builder {
    private SoyTypeRegistry registry;
    // TODO(lukes): combine with the print directive map
    private PluginResolver pluginResolver;
    private ImmutableList<? extends SoyPrintDirective> soyPrintDirectives;
    private ErrorReporter errorReporter;
    private SoyGeneralOptions options;
    private Optional<CssRegistry> cssRegistry;
    private boolean allowUnknownGlobals;
    private boolean allowV1Expression;
    private boolean allowUnknownJsGlobals;
    private boolean disableAllTypeChecking;
    private boolean desugarHtmlAndStateNodes = true;
    private boolean optimize = true;
    private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;
    private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;
    private boolean insertEscapingDirectives = true;
    private boolean addHtmlAttributesForDebugging = true;
    private final Map<Class<? extends CompilerPass>, PassContinuationRule>
        passContinuationRegistry = Maps.newHashMap();
    private boolean building;

    public Builder setErrorReporter(ErrorReporter errorReporter) {
      this.errorReporter = checkNotNull(errorReporter);
      return this;
    }

    public Builder setSoyPrintDirectives(
        ImmutableList<? extends SoyPrintDirective> printDirectives) {
      this.soyPrintDirectives = checkNotNull(printDirectives);
      return this;
    }

    public Builder setTypeRegistry(SoyTypeRegistry registry) {
      this.registry = checkNotNull(registry);
      return this;
    }

    public Builder setCssRegistry(Optional<CssRegistry> registry) {
      this.cssRegistry = registry;
      return this;
    }

    public Builder setPluginResolver(PluginResolver pluginResolver) {
      this.pluginResolver = pluginResolver;
      return this;
    }

    public Builder setGeneralOptions(SoyGeneralOptions options) {
      this.options = options;
      return this;
    }

    /**
     * Disables all the passes which enforce and rely on type information.
     *
     * <p>This should only be used for things like message extraction which doesn't tend to be
     * configured with a type registry.
     */
    public Builder disableAllTypeChecking() {
      this.disableAllTypeChecking = true;
      return this;
    }

    /**
     * Allows unknown global references.
     *
     * <p>This option is only available for backwards compatibility with legacy js only templates
     * and for parseinfo generation.
     */
    public Builder allowUnknownGlobals() {
      this.allowUnknownGlobals = true;
      return this;
    }

    /**
     * Allows v1Expression().
     *
     * <p>This option is only available for backwards compatibility with legacy JS only templates.
     */
    public Builder allowV1Expression() {
      this.allowV1Expression = true;
      return this;
    }

    /**
     * Allows the unknownJsGlobal() function to be used.
     *
     * <p>This option is only available for backwards compatibility with legacy JS only templates.
     */
    public Builder allowUnknownJsGlobals() {
      this.allowUnknownJsGlobals = true;
      return this;
    }

    /**
     * Whether to turn all the html nodes back into raw text nodes before code generation.
     *
     * <p>The default is {@code true}.
     */
    public Builder desugarHtmlAndStateNodes(boolean desugarHtmlAndStateNodes) {
      this.desugarHtmlAndStateNodes = desugarHtmlAndStateNodes;
      return this;
    }

    /**
     * Whether to run any of the optimization passes.
     *
     * <p>The default is {@code true}.
     */
    public Builder optimize(boolean optimize) {
      this.optimize = optimize;
      return this;
    }

    public Builder addHtmlAttributesForDebugging(boolean addHtmlAttributesForDebugging) {
      this.addHtmlAttributesForDebugging = addHtmlAttributesForDebugging;
      return this;
    }

    /** Configures this passmanager to run the conformance pass using the given config object. */
    public Builder setConformanceConfig(ValidatedConformanceConfig conformanceConfig) {
      this.conformanceConfig = checkNotNull(conformanceConfig);
      return this;
    }

    public Builder setLoggingConfig(ValidatedLoggingConfig loggingConfig) {
      this.loggingConfig = checkNotNull(loggingConfig);
      return this;
    }

    /**
     * Can be used to enable/disable the autoescaper.
     *
     * <p>The autoescaper is enabled by default.
     */
    public Builder insertEscapingDirectives(boolean insertEscapingDirectives) {
      this.insertEscapingDirectives = insertEscapingDirectives;
      return this;
    }

    /**
     * Registers a pass continuation rule.
     *
     * <p>By default, compilation continues after each pass. You can stop compilation before or
     * after any pass. This is useful for testing, or for running certain kinds of passes, such as
     * conformance-only compilations.
     *
     * <p>This method overwrites any previously registered rule.
     */
    public Builder addPassContinuationRule(
        Class<? extends CompilerPass> pass, PassContinuationRule rule) {
      checkNotNull(rule);
      passContinuationRegistry.put(pass, rule);
      return this;
    }

    public PassManager build() {
      // Single file passes
      // These passes perform tree rewriting and all compiler checks that don't require information
      // about callees.
      // Note that we try to run all of the single file passes to report as many errors as possible,
      // meaning that errors reported in earlier passes do not prevent running subsequent passes.
      building = true;
      ImmutableList.Builder<CompilerFilePass> singleFilePassesBuilder = ImmutableList.builder();
      // TOOD(b/22389927): enable the non-null assertion operator once we're ready to use for
      // fixing proto nullability.
      if (!options.getExperimentalFeatures().contains("enableNonNullAssertionOperator")) {
        addPass(new BanNonNullAssertionOperatorPass(errorReporter), singleFilePassesBuilder);
      }
      addPass(new DesugarGroupNodesPass(), singleFilePassesBuilder);
      // Needs to run after htmlrewriting, before ResolveNames, ResolveTemplateParamTypes and
      // autoescaping.
      addPass(new ContentSecurityPolicyNonceInjectionPass(errorReporter), singleFilePassesBuilder);
      addPass(new BasicHtmlValidationPass(errorReporter), singleFilePassesBuilder);

      // Needs to run after HtmlRewritePass since it produces the HtmlTagNodes that we use
      // to create placeholders.
      addPass(new InsertMsgPlaceholderNodesPass(errorReporter), singleFilePassesBuilder);

      // can run anywhere
      addPass(new CheckEscapingSanityFilePass(errorReporter), singleFilePassesBuilder);

      // TODO(b/157519545): Resolve template imports (and calls to imports) here, once we decouple
      // the imports pass from the template registry in cl/316826822. Then we won't need to
      // duplicate these passes.
      // Until then, we need this to resolve template names for data="all" calls. Normally this
      // could just be done in the later run of this pass, but if other files in the file set have
      // parse errors, then the file set passes won't be run, and parser will crash when it tries to
      // create the DataAllCallSituation for the TemplateRegistry.
      addPass(
          new ResolveTemplateNamesPass(errorReporter, /* throwErrorIfCantResolve= */ false),
          singleFilePassesBuilder);

      // Fileset passes run on all sources files and have access to a partial template registry so
      // they can examine information about dependencies.
      // TODO(b/158474755): Try to simplify this pass structure structure once we have template
      // imports.
      ImmutableList.Builder<CompilerFileSetPass> partialTemplateRegistryPassesBuilder =
          ImmutableList.builder();
      addPass(
          new ResolveProtoImportsPass(registry, errorReporter, disableAllTypeChecking),
          partialTemplateRegistryPassesBuilder);
      addPass(
          new ResolveTemplateImportsFromDepsPass(errorReporter),
          partialTemplateRegistryPassesBuilder);
      addPass(
          new ResolveTemplateNamesPass(errorReporter, /* throwErrorIfCantResolve= */ false),
          partialTemplateRegistryPassesBuilder);
      // needs to come early since it is necessary to create template metadata objects for
      // header compilation
      addPass(
          new ResolveTemplateParamTypesPass(errorReporter, disableAllTypeChecking),
          partialTemplateRegistryPassesBuilder);

      // needs to come before SoyConformancePass
      addPass(
          new ResolvePluginsPass(pluginResolver, errorReporter),
          partialTemplateRegistryPassesBuilder);

      // Must come after ResolvePluginsPass.
      addPass(new RewriteRemaindersPass(errorReporter), partialTemplateRegistryPassesBuilder);
      addPass(new RewriteGenderMsgsPass(errorReporter), partialTemplateRegistryPassesBuilder);
      // Needs to come after any pass that manipulates msg placeholders.
      addPass(
          new CalculateMsgSubstitutionInfoPass(errorReporter),
          partialTemplateRegistryPassesBuilder);
      addPass(new CheckNonEmptyMsgNodesPass(errorReporter), partialTemplateRegistryPassesBuilder);

      // Run before the RewriteGlobalsPass as it removes some globals.
      addPass(new VeRewritePass(), partialTemplateRegistryPassesBuilder);
      addPass(
          new RewriteGlobalsPass(options.getCompileTimeGlobals(), errorReporter),
          partialTemplateRegistryPassesBuilder);
      // needs to happen after rewrite globals
      addPass(new XidPass(errorReporter), partialTemplateRegistryPassesBuilder);
      // Needs to be before ResolveNamesPass.
      addPass(
          new V1ExpressionPass(allowV1Expression, errorReporter),
          partialTemplateRegistryPassesBuilder);
      addPass(
          new UnknownJsGlobalPass(allowUnknownJsGlobals, errorReporter),
          partialTemplateRegistryPassesBuilder);
      addPass(new ResolveNamesPass(errorReporter), partialTemplateRegistryPassesBuilder);
      // needs to be after ResolveNames and MsgsPass
      addPass(new MsgWithIdFunctionPass(errorReporter), partialTemplateRegistryPassesBuilder);

      // The StrictHtmlValidatorPass needs to run after ResolveNames.
      addPass(new StrictHtmlValidationPass(errorReporter), partialTemplateRegistryPassesBuilder);
      if (addHtmlAttributesForDebugging) {
        // needs to run after MsgsPass (so we don't mess up the auto placeholder naming algorithm)
        // and before ResolveExpressionTypesPass (since we insert expressions).
        addPass(new AddDebugAttributesPass(), partialTemplateRegistryPassesBuilder);
      }
      if (!disableAllTypeChecking) {
        addPass(new CheckDeclaredTypesPass(errorReporter), partialTemplateRegistryPassesBuilder);
        // Run before ResolveExpressionTypesPass since this makes type analysis on null safe
        // accesses simpler.
        addPass(new NullSafeAccessPass(), partialTemplateRegistryPassesBuilder);

        addPass(
            new ResolveExpressionTypesPass(errorReporter, loggingConfig, pluginResolver),
            partialTemplateRegistryPassesBuilder);
        // After ResolveExpressionTypesPass because ResolveExpressionTypesPass verifies usage and
        // types of non-null assertion operators.
        addPass(new SimplifyAssertNonNullPass(), partialTemplateRegistryPassesBuilder);
        // Needs to come after types have been set.
        addPass(
            new ValidatePrintExpressionTypes(errorReporter), partialTemplateRegistryPassesBuilder);
        // Needs to come after types have been set.
        addPass(
            new EnforceExperimentalFeaturesPass(options.getExperimentalFeatures(), errorReporter),
            partialTemplateRegistryPassesBuilder);
        addPass(new VeLogRewritePass(), partialTemplateRegistryPassesBuilder);
        // Needs to run before CheckGlobalsPass to prevent unbound global errors on the getExtension
        // parameters.
        if (!allowUnknownGlobals) {
          addPass(new GetExtensionRewriteParamPass(), partialTemplateRegistryPassesBuilder);
        }
      }

      // The check conformance pass needs to run on the rewritten html nodes, so it must run after
      // HtmlRewritePass. Because conformance exits abruptly after this pass we must ensure that the
      // AST is left in a complete state. Therefore this pass should also come after
      // ResolveExpressionTypesPass and others.
      addPass(
          new SoyConformancePass(conformanceConfig, errorReporter),
          partialTemplateRegistryPassesBuilder);
      addPass(
          new ResolvePackageRelativeCssNamesPass(errorReporter),
          partialTemplateRegistryPassesBuilder);

      if (!allowUnknownGlobals) {
        // Must come after RewriteGlobalsPass since that is when values are substituted.
        // We should also run after the ResolveNamesPass which checks for global/param ambiguity and
        // may issue better error messages.
        addPass(new CheckGlobalsPass(errorReporter), partialTemplateRegistryPassesBuilder);
      }
      addPass(
          new ValidateAliasesPass(errorReporter, options, loggingConfig),
          partialTemplateRegistryPassesBuilder);
      // Needs to run after HtmlRewritePass.
      addPass(
          new KeyCommandPass(errorReporter, disableAllTypeChecking),
          partialTemplateRegistryPassesBuilder);
      addPass(new ValidateSkipNodesPass(errorReporter), partialTemplateRegistryPassesBuilder);

      // Needs to run after HtmlRewritePass and StrictHtmlValidationPass (for single root
      // validation).
      addPass(new SoyElementPass(errorReporter), partialTemplateRegistryPassesBuilder);
      addPass(new CallAnnotationPass(), partialTemplateRegistryPassesBuilder);
      if (!disableAllTypeChecking) {
        addPass(
            new VeLogValidationPass(errorReporter, registry), partialTemplateRegistryPassesBuilder);
      }
      // Cross template checking passes

      // Fileset passes run on all sources files and have access to a template registry so they can
      // examine information about dependencies. These are naturally more expensive and should be
      // reserved for checks that require transitive call information (or full delegate sets).
      // Notably, the results of these passes cannot be cached in the AST cache.  So minimize their
      // use.
      ImmutableList.Builder<CompilerFileSetPass> crossTemplateCheckingPassesBuilder =
          ImmutableList.builder();
      addPass(
          new ResolveTemplateImportsFromFileSetPass(errorReporter),
          crossTemplateCheckingPassesBuilder);
      addPass(
          new ResolveTemplateNamesPass(errorReporter, /* throwErrorIfCantResolve= */ true),
          crossTemplateCheckingPassesBuilder);

      addPass(new CheckTemplateHeaderVarsPass(errorReporter), crossTemplateCheckingPassesBuilder);
      if (!disableAllTypeChecking) {
        // Upgrade the "named template" placeholder types to proper template types, now that their
        // signatures are known.
        addPass(
            new UpgradeTemplateTypesPass(registry, errorReporter),
            crossTemplateCheckingPassesBuilder);
        // Make sure we really upgraded *all* the template types.
        addPass(new CheckNoNamedTemplateTypesPass(), crossTemplateCheckingPassesBuilder);
        // Run type template-type-specific validation passes now that template types have been
        // resolved.
        addPass(new TemplateTypeValidationPass(errorReporter), crossTemplateCheckingPassesBuilder);
        addPass(new CheckTemplateCallsPass(errorReporter), crossTemplateCheckingPassesBuilder);
      }
      addPass(new CheckTemplateVisibilityPass(errorReporter), crossTemplateCheckingPassesBuilder);
      addPass(new CheckDelegatesPass(errorReporter), crossTemplateCheckingPassesBuilder);
      // If disallowing external calls, perform the check.
      if (options.allowExternalCalls() == TriState.DISABLED) {
        addPass(new StrictDepsPass(errorReporter), crossTemplateCheckingPassesBuilder);
      }

      addPass(new CombineConsecutiveRawTextNodesPass(), crossTemplateCheckingPassesBuilder);
      addPass(
          new AutoescaperPass(errorReporter, soyPrintDirectives, insertEscapingDirectives),
          crossTemplateCheckingPassesBuilder);
      // Relies on information from the autoescaper and valid type information
      if (!disableAllTypeChecking && insertEscapingDirectives) {
        addPass(new CheckBadContextualUsagePass(errorReporter), crossTemplateCheckingPassesBuilder);
      }

      // Simplification Passes.
      // These tend to simplify or canonicalize the tree in order to simplify the task of code
      // generation.

      if (desugarHtmlAndStateNodes) {
        // always desugar before the end since the backends (besides incremental dom) cannot handle
        // the nodes.
        addPass(new DesugarHtmlNodesPass(), crossTemplateCheckingPassesBuilder);
        addPass(new DesugarStateNodesPass(), crossTemplateCheckingPassesBuilder);
      }
      if (optimize) {
        addPass(new OptimizationPass(), crossTemplateCheckingPassesBuilder);
      }
      // DesugarHtmlNodesPass may chop up RawTextNodes, and OptimizationPass may produce additional
      // RawTextNodes. Stich them back together here.
      addPass(new CombineConsecutiveRawTextNodesPass(), crossTemplateCheckingPassesBuilder);

      building = false;
      if (!passContinuationRegistry.isEmpty()) {
        throw new IllegalStateException(
            "The following continuation rules don't match any pass: " + passContinuationRegistry);
      }
      return new PassManager(
          singleFilePassesBuilder.build(),
          partialTemplateRegistryPassesBuilder.build(),
          crossTemplateCheckingPassesBuilder.build());
    }

    /**
     * Adds the pass as a file set pass; if {@code pass} is a {@link CompilerFilePass} and doesn't
     * also implement {@link CompilerFileSetPass}, this manually wraps it as a file set pass.
     *
     * <p>The structure of the two overloads & {@code addPassInternal} is because we need a way to
     * do filePassAsFileSetPass without having ambgious method references for addPassInternal (when
     * a pass implements both the file set & file pass interfaces).
     */
    void addPass(CompilerPass pass, ImmutableList.Builder<CompilerFileSetPass> passBuilder) {
      // casts in this method.
      if (pass instanceof CompilerFileSetPass) {
        addPassInternal((CompilerFileSetPass) pass, passBuilder);
        return;
      }
      addPassInternal(filePassAsFileSetPass((CompilerFilePass) pass), passBuilder);
    }

    void addPass(CompilerFilePass pass, ImmutableList.Builder<CompilerFilePass> passBuilder) {
      addPassInternal(pass, passBuilder);
    }

    private <T extends CompilerPass> void addPassInternal(
        T pass, ImmutableList.Builder<T> builder) {

      Class<?> passClass = getPassClass(pass);
      PassContinuationRule rule = passContinuationRegistry.remove(passClass);
      if (!building) {
        return;
      }
      if (rule == null) {
        builder.add(pass);
        return;
      }
      switch (rule) {
        case STOP_AFTER_PASS:
          builder.add(pass);
          // fall-through
        case STOP_BEFORE_PASS:
          building = false;
          return;
      }
      throw new AssertionError("unhandled rule: " + rule);
    }
  }

  private static Class<? extends CompilerPass> getPassClass(CompilerPass pass) {
    return pass instanceof CompilerFilePassToFileSetPassShim
        ? ((CompilerFilePassToFileSetPassShim) pass).getDelegateClass()
        : pass.getClass();
  }
}
