/*
 * Copyright 2019 Google Inc.
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

package com.google.template.soy.plugin.internal;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.ErrorReporter.Checkpoint;
import com.google.template.soy.plugin.java.internal.JavaPluginValidator;
import com.google.template.soy.plugin.java.internal.ValidatorErrorReporter;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.soyparse.SoyFileParser;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.UnknownType;
import com.google.template.soy.types.ast.TypeNode;
import com.google.template.soy.types.ast.TypeNodeConverter;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Validates all source functions. */
public final class PluginValidator {

  private final SoyTypeRegistry typeRegistry;
  private final ErrorReporter errorReporter;
  private final ImmutableList<File> pluginRuntimeJars; // TODO(b/136201469): use this
  private final JavaPluginValidator javaValidator;

  public PluginValidator(
      ErrorReporter errorReporter, SoyTypeRegistry typeRegistry, List<File> pluginRuntimeJars) {
    this.typeRegistry = typeRegistry;
    this.errorReporter = errorReporter;
    this.pluginRuntimeJars = ImmutableList.copyOf(pluginRuntimeJars);
    this.javaValidator = new JavaPluginValidator(errorReporter, typeRegistry);
  }

  public void validate(Map<String, SoySourceFunction> fns) {
    for (Map.Entry<String, SoySourceFunction> fn : fns.entrySet()) {
      if (fn.getValue() instanceof SoyJavaSourceFunction) {
        validateJavaFunction(fn.getKey(), (SoyJavaSourceFunction) fn.getValue());
      }
    }
  }

  private void validateJavaFunction(String fnName, SoyJavaSourceFunction fn) {
    SourceLocation location = new SourceLocation(fn.getClass().getName());
    ValidatorErrorReporter validatorReporter =
        new ValidatorErrorReporter(
            errorReporter,
            fnName,
            fn.getClass(),
            UnknownType.getInstance(),
            location,
            /* includeTriggeredInTemplateMsg= */ false);
    SoyFunctionSignature fnSig = fn.getClass().getAnnotation(SoyFunctionSignature.class);
    for (Signature sig : fnSig.value()) {
      Checkpoint checkpoint = errorReporter.checkpoint();
      List<SoyType> paramTypes =
          Arrays.stream(sig.parameterTypes())
              .map(p -> typeFor(p, fn.getClass(), validatorReporter))
              .collect(toImmutableList());
      SoyType returnType = typeFor(sig.returnType(), fn.getClass(), validatorReporter);
      // If we errored just getting the types, then don't bother reporting more errors during
      // validation.
      if (!errorReporter.errorsSince(checkpoint)) {
        javaValidator.validate(
            fnName,
            fn,
            paramTypes,
            returnType,
            location,
            /* includeTriggeredInTemplateMsg= */ false);
      }
    }
  }

  private SoyType typeFor(
      String typeStr,
      Class<? extends SoySourceFunction> fnClass,
      ValidatorErrorReporter validatorReporter) {
    ErrorReporter localReporter = ErrorReporter.create(ImmutableMap.of());
    TypeNode typeNode = SoyFileParser.parseType(typeStr, fnClass.getName(), localReporter);
    SoyType type =
        typeNode == null
            ? UnknownType.getInstance()
            : new TypeNodeConverter(localReporter, typeRegistry).getOrCreateType(typeNode);
    // If any errors occurred while parsing the signature, wrap the errors in a more meaningful
    // message tailored to the plugin implementation.
    validatorReporter.wrapErrors(localReporter.getErrors());
    validatorReporter.wrapWarnings(localReporter.getWarnings());

    return type;
  }
}
