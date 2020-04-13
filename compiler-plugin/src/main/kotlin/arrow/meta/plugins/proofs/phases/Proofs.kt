package arrow.meta.plugins.proofs.phases

import arrow.meta.dsl.platform.cli
import arrow.meta.phases.CompilerContext
import arrow.meta.phases.resolve.baseLineTypeChecker
import arrow.meta.plugins.proofs.phases.resolve.cache.initializeProofCache
import arrow.meta.plugins.proofs.phases.resolve.scopes.discardPlatformBaseObjectFakeOverrides
import arrow.meta.plugins.proofs.phases.resolve.matchingCandidates
import arrow.meta.plugins.proofs.phases.resolve.cache.proofCache
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.unwrappedGetMethod
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjection

val ArrowExtensionProof: FqName = FqName("arrow.Extension")
val ArrowGivenProof: FqName = FqName("arrow.Given")
val ArrowCoercionProof: FqName = FqName("arrow.Coercion")
val ArrowRefinementProof: FqName = FqName("arrow.Refinement")

val ArrowProofSet: Set<FqName> = setOf(
  ArrowExtensionProof,
  ArrowGivenProof,
  ArrowCoercionProof,
  ArrowRefinementProof
)

sealed class Proof(
  open val to: KotlinType,
  open val through: CallableMemberDescriptor
) {

  val underliyingFunctionDescriptor: FunctionDescriptor?
    get() = when (val f = through) {
      is FunctionDescriptor -> f
      is PropertyDescriptor -> f.unwrappedGetMethod ?: TODO("Unsupported $f as @given")
      is ClassDescriptor -> f.unsubstitutedPrimaryConstructor
      else -> TODO("Unsupported $f as @given")
    }

  inline fun <A> fold(
    given: GivenProof.() -> A,
    coercion: CoercionProof.() -> A,
    projection: ProjectionProof.() -> A,
    refinement: RefinementProof.() -> A
  ): A =
    when (this) {
      is GivenProof -> given(this)
      is CoercionProof -> coercion(this)
      is ProjectionProof -> projection(this)
      is RefinementProof -> refinement(this)
    }
}

data class GivenProof(
  override val to: KotlinType,
  override val through: CallableMemberDescriptor
) : Proof(to, through)

sealed class ExtensionProof(
  open val from: KotlinType,
  override val to: KotlinType,
  override val through: FunctionDescriptor,
  open val coerce: Boolean = false
) : Proof(to, through)

data class CoercionProof(
  override val from: KotlinType,
  override val to: KotlinType,
  override val through: FunctionDescriptor
) : ExtensionProof(from, to, through, true)

data class ProjectionProof(
  override val from: KotlinType,
  override val to: KotlinType,
  override val through: FunctionDescriptor
) : ExtensionProof(from, to, through, false)

data class RefinementProof(
  val from: KotlinType,
  override val to: KotlinType,
  override val through: CallableMemberDescriptor,
  val coerce: Boolean = true
) : Proof(to, through)

fun CallableMemberDescriptor.isProof(): Boolean =
  ArrowProofSet.any(annotations::hasAnnotation)

fun CompilerContext.extending(types: Collection<KotlinType>): List<ExtensionProof> =
  types.flatMap { extensionProofs(it, it.constructor.builtIns.nullableAnyType) }

fun Proof.callables(descriptorNameFilter: (Name) -> Boolean = { true }): List<CallableMemberDescriptor> =
  to.memberScope
    .getContributedDescriptors(nameFilter = descriptorNameFilter)
    .toList()
    .filterIsInstance<CallableMemberDescriptor>()
    .mapNotNull(CallableMemberDescriptor::discardPlatformBaseObjectFakeOverrides)

fun CompilerContext.extensionProof(subType: KotlinType, superType: KotlinType): Proof? =
  extensionProofs(subType, superType).firstOrNull()

fun CompilerContext.extensionProofs(subType: KotlinType, superType: KotlinType): List<ExtensionProof> =
  module.proofs.filterIsInstance<ExtensionProof>()
    .matchingCandidates(this, subType, superType)

fun CompilerContext.givenProofs(superType: KotlinType): List<GivenProof> =
  module.proofs.filterIsInstance<GivenProof>()
    .matchingCandidates(this, superType)

fun CompilerContext.coerceProof(subType: KotlinType, superType: KotlinType): ExtensionProof? =
  coerceProofs(subType, superType).firstOrNull()

fun CompilerContext.coerceProofs(subType: KotlinType, superType: KotlinType): List<ExtensionProof> =
  module.proofs
    .filterIsInstance<ExtensionProof>()
    .filter { it.coerce }
    .matchingCandidates(this, subType, superType)

fun CompilerContext.areTypesCoerced(subType: KotlinType, supertype: KotlinType): Boolean {
  val isSubtypeOf = baseLineTypeChecker.isSubtypeOf(subType, supertype)

  return if (!isSubtypeOf) {
    val isProofSubtype = ctx.coerceProof(subType, supertype) != null

    !isSubtypeOf && isProofSubtype

  } else false
}

val ModuleDescriptor.proofs: List<Proof>
  get() =
    if (this is ModuleDescriptorImpl) {
      try {
        val cacheValue = proofCache[this]
        when {
          cacheValue != null -> {
            cacheValue.proofs
          }
          else -> cli { initializeProofCache() } ?: emptyList()
        }
      } catch (e: RuntimeException) {
        println("TODO() Detected exception: ${e.printStackTrace()}")
        emptyList<Proof>()
      }
    } else emptyList()