// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.android.AndroidConfiguration.AndroidAaptVersion;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Objects;
import javax.annotation.Nullable;

/** Wraps this target's Android assets */
public class AndroidAssets {
  private static final String ASSETS_ATTR = "assets";
  private static final String ASSETS_DIR_ATTR = "assets_dir";

  /**
   * Validates that either neither or both of assets and assets_dir are set.
   *
   * <p>TODO(b/77574966): Remove this method and just validate assets as part of {@link
   * #from(RuleErrorConsumer, Iterable, PathFragment)} once assets are fully decoupled from
   * resources.
   *
   * @deprecated Instead, validate assets as part of creating an AndroidAssets object.
   */
  @Deprecated
  static void validateAssetsAndAssetsDir(RuleContext ruleContext) throws RuleErrorException {
    validateAssetsAndAssetsDir(ruleContext, getAssetTargets(ruleContext), getAssetDir(ruleContext));
  }

  private static void validateAssetsAndAssetsDir(
      RuleErrorConsumer errorConsumer,
      @Nullable Iterable<? extends TransitiveInfoCollection> assetTargets,
      @Nullable PathFragment assetsDir)
      throws RuleErrorException {
    if (assetTargets == null ^ assetsDir == null) {
      errorConsumer.throwWithRuleError(
          String.format(
              "'%s' and '%s' should be either both empty or both non-empty",
              ASSETS_ATTR, ASSETS_DIR_ATTR));
    }
  }

  /** Collects this rule's android assets, based on rule attributes. */
  public static AndroidAssets from(RuleContext ruleContext) throws RuleErrorException {
    return from(ruleContext, getAssetTargets(ruleContext), getAssetDir(ruleContext));
  }

  /** Collects Android assets from the specified values */
  public static AndroidAssets from(
      RuleErrorConsumer errorConsumer,
      @Nullable Iterable<? extends TransitiveInfoCollection> assetTargets,
      @Nullable PathFragment assetsDir)
      throws RuleErrorException {
    validateAssetsAndAssetsDir(errorConsumer, assetTargets, assetsDir);

    if (assetTargets == null) {
      return empty();
    }

    ImmutableList.Builder<Artifact> assets = ImmutableList.builder();
    ImmutableList.Builder<PathFragment> assetRoots = ImmutableList.builder();

    for (TransitiveInfoCollection target : assetTargets) {
      for (Artifact file : target.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        PathFragment packageFragment = file.getOwnerLabel().getPackageIdentifier().getSourceRoot();
        PathFragment packageRelativePath = file.getRootRelativePath().relativeTo(packageFragment);
        if (packageRelativePath.startsWith(assetsDir)) {
          PathFragment relativePath = packageRelativePath.relativeTo(assetsDir);
          PathFragment path = file.getExecPath();
          assetRoots.add(path.subFragment(0, path.segmentCount() - relativePath.segmentCount()));
        } else {
          errorConsumer.throwWithAttributeError(
              ASSETS_ATTR,
              String.format(
                  "'%s' (generated by '%s') is not beneath '%s'",
                  file.getRootRelativePath(), target.getLabel(), assetsDir));
        }
        assets.add(file);
      }
    }

    return new AndroidAssets(assets.build(), assetRoots.build(), assetsDir.getPathString());
  }

  @Nullable
  private static Iterable<? extends TransitiveInfoCollection> getAssetTargets(
      RuleContext ruleContext) {
    if (!ruleContext.attributes().isAttributeValueExplicitlySpecified(ASSETS_ATTR)) {
      return null;
    }

    return ruleContext.getPrerequisitesIf(ASSETS_ATTR, Mode.TARGET, FileProvider.class);
  }

  @Nullable
  private static PathFragment getAssetDir(RuleContext ruleContext) {
    if (!ruleContext.attributes().isAttributeValueExplicitlySpecified(ASSETS_DIR_ATTR)) {
      return null;
    }
    return PathFragment.create(ruleContext.attributes().get(ASSETS_DIR_ATTR, Type.STRING));
  }

  /**
   * Creates a {@link AndroidAssets} containing all the assets in a directory artifact, for use with
   * AarImport rules.
   *
   * <p>In general, {@link #from(RuleContext)} should be used instead, but it can't be for AarImport
   * since we don't know about its individual assets at analysis time.
   *
   * @param assetsDir the tree artifact containing a {@code assets/} directory
   */
  static AndroidAssets forAarImport(SpecialArtifact assetsDir) {
    Preconditions.checkArgument(assetsDir.isTreeArtifact());
    return new AndroidAssets(
        ImmutableList.of(assetsDir),
        ImmutableList.of(assetsDir.getExecPath().getChild("assets")),
        assetsDir.getExecPathString());
  }

  public static AndroidAssets empty() {
    return new AndroidAssets(ImmutableList.of(), ImmutableList.of(), /* assetDir = */ null);
  }

  private final ImmutableList<Artifact> assets;
  private final ImmutableList<PathFragment> assetRoots;
  private final @Nullable String assetDir;

  AndroidAssets(AndroidAssets other) {
    this(other.assets, other.assetRoots, other.assetDir);
  }

  @VisibleForTesting
  AndroidAssets(
      ImmutableList<Artifact> assets,
      ImmutableList<PathFragment> assetRoots,
      @Nullable String assetDir) {
    this.assets = assets;
    this.assetRoots = assetRoots;
    this.assetDir = assetDir;
  }

  public ImmutableList<Artifact> getAssets() {
    return assets;
  }

  public ImmutableList<PathFragment> getAssetRoots() {
    return assetRoots;
  }

  public @Nullable String getAssetDirAsString() {
    return assetDir;
  }

  public ParsedAndroidAssets parse(AndroidDataContext dataContext, AndroidAaptVersion aaptVersion)
      throws InterruptedException {
    return ParsedAndroidAssets.parseFrom(dataContext, aaptVersion, this);
  }

  /** Convenience method to do all of asset processing - parsing and merging. */
  public MergedAndroidAssets process(
      AndroidDataContext dataContext, AssetDependencies assetDeps, AndroidAaptVersion aaptVersion)
      throws InterruptedException {
    return parse(dataContext, aaptVersion).merge(dataContext, assetDeps);
  }

  @Override
  public boolean equals(Object object) {
    if (object == null || getClass() != object.getClass()) {
      return false;
    }

    AndroidAssets other = (AndroidAssets) object;
    return assets.equals(other.assets) && assetRoots.equals(other.assetRoots);
  }

  @Override
  public int hashCode() {
    return Objects.hash(assets, assetRoots);
  }
}
