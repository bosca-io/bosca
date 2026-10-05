use firebase_scrypt::FirebaseScrypt;

#[derive(uniffi::Object)]
struct PasswordUtil {
    scrypt: FirebaseScrypt
}

#[uniffi::export]
impl PasswordUtil {
    #[uniffi::constructor]
    fn new(
        base64_salt_separator: String,
        base64_signer_key: String,
        mem_cost: u32,
        rounds: u32
    ) -> Self {
        Self {
            scrypt: FirebaseScrypt::new(&base64_salt_separator, &base64_signer_key, rounds, mem_cost)
        }
    }

    fn matches(
        &self,
        salt: String,
        password_hash: String,
        password: String
    ) -> bool {
        if let Ok(result) = self.scrypt.verify_password(&password, &salt, &password_hash) {
            result
        } else {
            false
        }
    }
}

uniffi::setup_scaffolding!();
