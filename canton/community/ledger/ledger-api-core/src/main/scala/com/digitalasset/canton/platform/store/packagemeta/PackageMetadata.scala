// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.packagemeta

import cats.kernel.Semigroup
import cats.syntax.semigroup.*
import com.daml.daml_lf_dev.DamlLf
import com.daml.lf.archive.Decode
import com.daml.lf.data.Ref
import com.daml.lf.language.LanguageVersion
import com.digitalasset.canton.platform.store.packagemeta.PackageMetadata.{
  InterfacesImplementedBy,
  PackageResolution,
}

import scala.math.Ordered.orderingToOrdered

final case class PackageMetadata(
    interfaces: Set[Ref.Identifier] = Set.empty,
    templates: Set[Ref.Identifier] = Set.empty,
    interfacesImplementedBy: InterfacesImplementedBy = Map.empty,
    packageNameMap: Map[Ref.PackageName, PackageResolution] = Map.empty,
    packageIdVersionMap: Map[Ref.PackageId, (Ref.PackageName, Ref.PackageVersion)] = Map.empty,
)

object PackageMetadata {
  type InterfacesImplementedBy = Map[Ref.Identifier, Set[Ref.Identifier]]

  final case class LocalPackagePreference(
      version: Ref.PackageVersion,
      packageId: Ref.PackageId,
  )

  final case class PackageResolution(
      preference: LocalPackagePreference,
      allPackageIdsForName: Set[Ref.PackageId],
  )

  def from(archive: DamlLf.Archive): PackageMetadata = {
    val ((packageId, pkg), packageInfo) = Decode.assertDecodeInfoPackage(archive)

    val packageLanguageVersion = pkg.languageVersion
    val nonUpgradablePackageMetadata = PackageMetadata(
      packageNameMap = Map.empty,
      interfaces = packageInfo.definedInterfaces,
      templates = packageInfo.definedTemplates,
      interfacesImplementedBy = packageInfo.interfaceInstances,
      packageIdVersionMap = Map.empty,
    )

    pkg.metadata
      .collect {
        case decodedPackageMeta
            // TODO(#16362): Replace with own feature
            if packageLanguageVersion >= LanguageVersion.Features.sharedKeys =>
          val packageName = decodedPackageMeta.name
          val packageVersion = decodedPackageMeta.version

          // Update with upgradable package metadata
          nonUpgradablePackageMetadata.copy(
            packageNameMap = Map(
              packageName -> PackageResolution(
                preference = LocalPackagePreference(packageVersion, packageId),
                allPackageIdsForName = Set(packageId),
              )
            ),
            packageIdVersionMap = Map(packageId -> (packageName, packageVersion)),
          )
      }
      .getOrElse(nonUpgradablePackageMetadata)
  }

  object Implicits {
    // Although the combine for the semigroups below is safely commutative,
    // we care about the order of the operands for performance considerations.
    // Specifically, we must keep the complexity linear in the size of the appended package
    // and NOT in the size of the current PackageMetadata state.
    implicit def packageMetadataSemigroup: Semigroup[PackageMetadata] =
      Semigroup.instance { case (x, y) =>
        PackageMetadata(
          packageNameMap = x.packageNameMap |+| y.packageNameMap,
          interfaces = x.interfaces |+| y.interfaces,
          templates = x.templates |+| y.templates,
          interfacesImplementedBy = x.interfacesImplementedBy |+| y.interfacesImplementedBy,
          packageIdVersionMap = y.packageIdVersionMap
            .foldLeft(x.packageIdVersionMap) { case (acc, (k, v)) =>
              acc.updatedWith(k) {
                case None => Some(v)
                case Some(existing) if existing == v => Some(v)
                case Some(existing) =>
                  throw new IllegalStateException(
                    s"Conflicting versioned package names for the same package id $k. Previous ($existing) vs uploaded($v)"
                  )
              }
            },
        )
      }

    implicit def upgradablePackageIdPriorityMapSemigroup: Semigroup[PackageResolution] =
      Semigroup.instance { case (x, y) =>
        PackageResolution(
          preference =
            if (y.preference.version > x.preference.version) y.preference else x.preference,
          allPackageIdsForName = x.allPackageIdsForName |+| y.allPackageIdsForName,
        )
      }
  }
}
