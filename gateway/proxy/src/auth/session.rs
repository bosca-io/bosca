use moka::future::Cache;
use uuid::Uuid;

use super::AuthenticatedUser;

#[derive(Clone)]
pub struct SessionStore {
    sessions: Cache<String, SessionData>,
    ttl_secs: u64,
}

#[derive(Clone, Debug)]
pub struct SessionData {
    pub user: AuthenticatedUser,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

impl SessionStore {
    pub fn new(ttl_secs: u64) -> Self {
        Self {
            sessions: Cache::builder()
                .time_to_live(std::time::Duration::from_secs(ttl_secs))
                .max_capacity(100_000)
                .build(),
            ttl_secs,
        }
    }

    /// Lifetime to advertise on the session cookie's `Max-Age`.
    pub fn ttl_secs(&self) -> u64 {
        self.ttl_secs
    }

    pub fn create_session_id() -> String {
        Uuid::new_v4().to_string()
    }

    pub async fn store(&self, session_id: &str, user: AuthenticatedUser) {
        self.sessions
            .insert(
                session_id.to_string(),
                SessionData {
                    user,
                    created_at: chrono::Utc::now(),
                },
            )
            .await;
    }

    pub async fn get(&self, session_id: &str) -> Option<SessionData> {
        self.sessions.get(session_id).await
    }

    pub async fn remove(&self, session_id: &str) {
        self.sessions.invalidate(session_id).await;
    }
}
