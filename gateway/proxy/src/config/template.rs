use crate::auth::AuthenticatedUser;

#[derive(Clone, Debug)]
pub enum HeaderTemplate {
    Static(String),
    Compiled(Vec<Segment>),
}

#[derive(Clone, Debug)]
pub enum Segment {
    Literal(String),
    Variable(TemplateVar),
}

#[derive(Clone, Debug)]
pub enum TemplateVar {
    UserSubject,
    UserEmail,
    UserName,
    UserGroups,
    RequestPath,
    RequestHost,
    /// Scheme (`http` / `https`) the proxy is serving. Derived from the
    /// gateway's own config (TLS cert present → https); not read from
    /// any client-supplied header, because the proxy strips
    /// `X-Forwarded-Proto` on the inbound path.
    RequestScheme,
    /// Port the proxy is bound to.
    RequestPort,
    /// Peer socket address as observed by axum's connection info. For
    /// a multi-hop setup (LB in front), this is the LB's address, not
    /// the original client. Trusting an inbound `X-Forwarded-For` here
    /// would let any client spoof a source IP.
    RequestClientIp,
    UpstreamName,
}

/// Per-request inputs to header-template rendering. Bundled in a
/// struct so adding a new variable doesn't churn every `render` call
/// site.
///
/// `user` is optional: anonymous routes (`auth_method = NONE`) still
/// run the inject loop so they can stamp `X-Forwarded-*` and similar
/// non-identity headers. A template that references `user.*` on an
/// anonymous route renders to `None`, which drops the header — same
/// semantics as a JWT subject without an email claim.
pub struct RenderContext<'a> {
    pub user: Option<&'a AuthenticatedUser>,
    pub request_path: &'a str,
    pub request_host: &'a str,
    pub request_scheme: &'a str,
    pub request_port: &'a str,
    pub request_client_ip: &'a str,
    pub upstream_name: &'a str,
}

impl HeaderTemplate {
    /// Compile a header template string. Unknown variable references
    /// (typos like `{{user.emial}}`) are logged at WARN severity so
    /// they don't silently ship as literal braces in the upstream
    /// header — for an identity gateway, silent identity-loss is a
    /// security-impacting bug.
    pub fn compile(input: &str) -> Self {
        Self::compile_with_context(input, None)
    }

    /// Like [`compile`] but accepts a `header_name` for diagnostics —
    /// emits actionable warnings when route templates contain
    /// unresolved variables.
    pub fn compile_with_context(input: &str, header_name: Option<&str>) -> Self {
        if !input.contains("{{") {
            return Self::Static(input.to_string());
        }
        let mut segments = Vec::new();
        let mut remaining = input;
        while let Some(start) = remaining.find("{{") {
            if start > 0 {
                segments.push(Segment::Literal(remaining[..start].to_string()));
            }
            let after_open = &remaining[start + 2..];
            if let Some(end) = after_open.find("}}") {
                let var_name = after_open[..end].trim();
                if let Some(var) = parse_variable(var_name) {
                    segments.push(Segment::Variable(var));
                } else {
                    tracing::warn!(
                        header = ?header_name,
                        variable = %var_name,
                        "header template references unknown variable; literal `{{{{...}}}}` will be forwarded upstream"
                    );
                    segments.push(Segment::Literal(format!("{{{{{}}}}}", var_name)));
                }
                remaining = &after_open[end + 2..];
            } else {
                tracing::warn!(
                    header = ?header_name,
                    "header template has an unclosed `{{{{` block"
                );
                // Push the unclosed-`{{` tail as a literal — the
                // prefix before it was already pushed above, so
                // pushing `remaining` here would emit the prefix
                // twice. The `&remaining[start..]` slice starts at
                // the offending `{{`.
                segments.push(Segment::Literal(remaining[start..].to_string()));
                remaining = "";
            }
        }
        if !remaining.is_empty() {
            segments.push(Segment::Literal(remaining.to_string()));
        }
        Self::Compiled(segments)
    }

    pub fn render(&self, ctx: &RenderContext<'_>) -> Option<String> {
        match self {
            Self::Static(s) => Some(s.clone()),
            Self::Compiled(segments) => {
                let mut result = String::new();
                for seg in segments {
                    match seg {
                        Segment::Literal(s) => result.push_str(s),
                        Segment::Variable(var) => {
                            let val = resolve_variable(var, ctx)?;
                            result.push_str(&val);
                        }
                    }
                }
                Some(result)
            }
        }
    }
}

fn parse_variable(name: &str) -> Option<TemplateVar> {
    match name {
        "user.subject" => Some(TemplateVar::UserSubject),
        "user.email" => Some(TemplateVar::UserEmail),
        "user.name" => Some(TemplateVar::UserName),
        "user.groups" => Some(TemplateVar::UserGroups),
        "request.path" => Some(TemplateVar::RequestPath),
        "request.host" => Some(TemplateVar::RequestHost),
        "request.scheme" => Some(TemplateVar::RequestScheme),
        "request.port" => Some(TemplateVar::RequestPort),
        // Accept both camelCase and snake_case so operators can pick
        // whichever fits the conventions of their existing routes —
        // nothing else in the variable set has been multi-word until
        // now, so we don't have an established style to enforce.
        "request.clientIp" | "request.client_ip" => Some(TemplateVar::RequestClientIp),
        "upstream.name" => Some(TemplateVar::UpstreamName),
        _ => None,
    }
}

fn resolve_variable(var: &TemplateVar, ctx: &RenderContext<'_>) -> Option<String> {
    match var {
        TemplateVar::UserSubject => Some(ctx.user?.subject.clone()),
        TemplateVar::UserEmail => ctx.user?.email.clone(),
        TemplateVar::UserName => ctx.user?.name.clone(),
        TemplateVar::UserGroups => Some(ctx.user?.groups.join(",")),
        TemplateVar::RequestPath => Some(ctx.request_path.to_string()),
        TemplateVar::RequestHost => Some(ctx.request_host.to_string()),
        TemplateVar::RequestScheme => Some(ctx.request_scheme.to_string()),
        TemplateVar::RequestPort => Some(ctx.request_port.to_string()),
        TemplateVar::RequestClientIp => {
            // Drop the header when we couldn't observe a peer IP —
            // sending an empty `X-Forwarded-For: ` looks malformed to
            // upstreams and obscures the real signal.
            if ctx.request_client_ip.is_empty() {
                None
            } else {
                Some(ctx.request_client_ip.to_string())
            }
        }
        TemplateVar::UpstreamName => Some(ctx.upstream_name.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::GatewayAuthMethod;

    fn user_with(email: Option<&str>, name: Option<&str>) -> AuthenticatedUser {
        AuthenticatedUser {
            subject: "alice-uuid".into(),
            email: email.map(String::from),
            name: name.map(String::from),
            groups: vec!["analysts".into(), "viewers".into()],
            scopes: None,
            auth_method: GatewayAuthMethod::Jwt,
        }
    }

    fn ctx<'a>(user: &'a AuthenticatedUser, host: &'a str, upstream: &'a str) -> RenderContext<'a> {
        RenderContext {
            user: Some(user),
            request_path: "/p",
            request_host: host,
            request_scheme: "https",
            request_port: "9999",
            request_client_ip: "10.0.0.1",
            upstream_name: upstream,
        }
    }

    #[test]
    fn user_variable_drops_header_on_anonymous_route() {
        // Without a user in the context, any header referencing
        // `user.*` is dropped — same semantics as a JWT subject
        // missing an optional claim.
        let context = RenderContext {
            user: None,
            request_path: "/p",
            request_host: "h",
            request_scheme: "https",
            request_port: "9999",
            request_client_ip: "10.0.0.1",
            upstream_name: "u",
        };
        let t = HeaderTemplate::compile("{{user.subject}}");
        assert!(t.render(&context).is_none());
    }

    #[test]
    fn request_variables_still_render_without_a_user() {
        // X-Forwarded-* on anonymous routes is the headline case for
        // making `user` optional — verify those variables still resolve.
        let context = RenderContext {
            user: None,
            request_path: "/p",
            request_host: "gw.example",
            request_scheme: "https",
            request_port: "9999",
            request_client_ip: "10.0.0.1",
            upstream_name: "u",
        };
        let t = HeaderTemplate::compile("{{request.scheme}}://{{request.host}}");
        assert_eq!(t.render(&context).as_deref(), Some("https://gw.example"));
    }

    #[test]
    fn static_template_renders_literally() {
        let t = HeaderTemplate::compile("bosca-gateway");
        assert!(matches!(t, HeaderTemplate::Static(_)));
        let r = t.render(&ctx(&user_with(Some("a@b"), None), "h", "u"));
        assert_eq!(r.as_deref(), Some("bosca-gateway"));
    }

    #[test]
    fn user_subject_substitutes() {
        let t = HeaderTemplate::compile("user-{{user.subject}}");
        let r = t.render(&ctx(&user_with(None, None), "h", "u"));
        assert_eq!(r.as_deref(), Some("user-alice-uuid"));
    }

    #[test]
    fn missing_optional_field_drops_the_header() {
        // The contract: if a referenced variable resolves to None
        // (e.g. user has no email), the whole header is omitted
        // rather than rendered as an empty string. This prevents
        // sending `X-Trino-User: ` upstream.
        let t = HeaderTemplate::compile("{{user.email}}");
        let r = t.render(&ctx(&user_with(None, None), "h", "u"));
        assert!(r.is_none(), "header should be dropped when email is None");
    }

    #[test]
    fn unknown_variable_compiles_to_literal_braces() {
        let t = HeaderTemplate::compile("hello {{user.emial}}");
        let r = t.render(&ctx(&user_with(Some("a@b"), None), "h", "u"));
        assert_eq!(r.as_deref(), Some("hello {{user.emial}}"));
    }

    #[test]
    fn unclosed_block_is_left_as_literal() {
        let t = HeaderTemplate::compile("hello {{user.email");
        let r = t.render(&ctx(&user_with(Some("a@b"), None), "h", "u"));
        assert_eq!(r.as_deref(), Some("hello {{user.email"));
    }

    #[test]
    fn whitespace_inside_braces_is_trimmed() {
        let t = HeaderTemplate::compile("v={{ user.subject }}");
        let r = t.render(&ctx(&user_with(None, None), "h", "u"));
        assert_eq!(r.as_deref(), Some("v=alice-uuid"));
    }

    #[test]
    fn multiple_substitutions_in_one_template() {
        let t = HeaderTemplate::compile("{{user.subject}}@{{request.host}}{{request.path}}");
        let r = t.render(&ctx(&user_with(None, None), "gw.example", "u"));
        assert_eq!(r.as_deref(), Some("alice-uuid@gw.example/p"));
    }

    #[test]
    fn user_groups_joins_with_comma() {
        let t = HeaderTemplate::compile("{{user.groups}}");
        let r = t.render(&ctx(&user_with(None, None), "h", "u"));
        assert_eq!(r.as_deref(), Some("analysts,viewers"));
    }

    #[test]
    fn upstream_name_substitutes() {
        let t = HeaderTemplate::compile("via {{upstream.name}}");
        let r = t.render(&ctx(&user_with(None, None), "h", "trino"));
        assert_eq!(r.as_deref(), Some("via trino"));
    }

    #[test]
    fn request_scheme_port_and_client_ip_render() {
        let t = HeaderTemplate::compile(
            "{{request.scheme}}://{{request.host}}:{{request.port}} from {{request.clientIp}}",
        );
        let r = t.render(&ctx(&user_with(None, None), "gw.example", "u"));
        assert_eq!(r.as_deref(), Some("https://gw.example:9999 from 10.0.0.1"),);
    }

    #[test]
    fn request_client_ip_accepts_snake_case_alias() {
        let t = HeaderTemplate::compile("{{request.client_ip}}");
        let r = t.render(&ctx(&user_with(None, None), "h", "u"));
        assert_eq!(r.as_deref(), Some("10.0.0.1"));
    }

    #[test]
    fn empty_client_ip_drops_the_header() {
        // `X-Forwarded-For: ` is a malformed value — drop it instead.
        let user = user_with(None, None);
        let context = RenderContext {
            user: Some(&user),
            request_path: "/p",
            request_host: "h",
            request_scheme: "https",
            request_port: "9999",
            request_client_ip: "",
            upstream_name: "u",
        };
        let t = HeaderTemplate::compile("{{request.clientIp}}");
        assert!(t.render(&context).is_none());
    }
}
