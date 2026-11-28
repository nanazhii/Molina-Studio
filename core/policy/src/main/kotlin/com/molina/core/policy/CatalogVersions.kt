package com.molina.core.policy

// DITURUNKAN dari gradle/libs.versions.toml oleh scripts/gen_catalog_versions.py. Jangan diedit manual:
// ubah catalog, lalu jalankan skrip itu (scripts/catalog_gate.py menggagalkan CI bila tidak sinkron).
/** Versi dependensi yang tampil di Komponen bawaan; satu sumber: gradle/libs.versions.toml. */
object CatalogVersions {
    const val SORA = "0.23.6"
    const val COMMONS_COMPRESS = "1.27.1"
    const val ZIP4J = "2.11.5"
    const val TUKAANI_XZ = "1.10"
}
