use std::{env, path::PathBuf};

fn main() {
    if env::var_os("CARGO_CFG_WINDOWS").is_none() {
        return;
    }

    let icon = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("assets")
        .join("phone-mouse.ico");
    println!("cargo:rerun-if-changed={}", icon.display());

    let mut resource = winres::WindowsResource::new();
    resource.set_icon(icon.to_string_lossy().as_ref());
    resource
        .compile()
        .expect("failed to embed the Windows application icon");
}
