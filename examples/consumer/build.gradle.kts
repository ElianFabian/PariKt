plugins {
	// Share AGP/KGP in the parent classloader, as in the PariKt development build.
	alias(pariktLibs.plugins.android.library) apply false
	alias(pariktLibs.plugins.android.kotlin.multiplatform.library) apply false
	alias(pariktLibs.plugins.kotlin.multiplatform) apply false
}
