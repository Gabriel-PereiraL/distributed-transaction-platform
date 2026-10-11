package dev.dtp.activity;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Only signed realm roles from the configured issuer are accepted. */
final class RealmAuthorities {
    private static final Set<String> ALLOWED = Set.of("transfer-client", "auditor", "operator", "admin");
    private RealmAuthorities() { }
    static Collection<GrantedAuthority> from(Jwt jwt) {
        var result = new ArrayList<GrantedAuthority>(new JwtGrantedAuthoritiesConverter().convert(jwt));
        Object realm = jwt.getClaims().get("realm_access");
        if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles && roles.size() <= 64) {
            for (Object value : roles) {
                if (value instanceof String role && ALLOWED.contains(role)) {
                    var authority = new SimpleGrantedAuthority("ROLE_" + role);
                    if (!result.contains(authority)) result.add(authority);
                }
            }
        }
        return java.util.List.copyOf(result);
    }
}
