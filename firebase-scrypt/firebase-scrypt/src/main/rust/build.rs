fn main() {
    println!("cargo::rerun-if-changed=build.rs");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        // Keep the Mach-O library identity independent of Cargo's checkout-specific output path.
        println!(
            "cargo::rustc-link-arg-cdylib=-Wl,-install_name,@rpath/libfirebase_scrypt_util.dylib"
        );
    }
}
