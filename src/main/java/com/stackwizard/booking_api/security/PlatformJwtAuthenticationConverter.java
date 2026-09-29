package com.stackwizard.booking_api.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts Platform JWT into Spring authorities: SCOPE_* from scopes plus ROLE_* from mikos_roles.
 */
@Component
public class PlatformJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        authorities.addAll(scopeAuthorities(jwt));
        authorities.addAll(roleAuthorities(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static Collection<? extends GrantedAuthority> scopeAuthorities(Jwt jwt) {
        Set<GrantedAuthority> out = new HashSet<>();
        Object scope = jwt.getClaim("scope");
        if (scope instanceof String s) {
            for (String part : s.split("\\s+")) {
                if (!part.isBlank()) {
                    out.add(new SimpleGrantedAuthority("SCOPE_" + part.trim()));
                }
            }
        } else if (scope instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(new SimpleGrantedAuthority("SCOPE_" + o.toString().trim()));
                }
            }
        }
        Object scp = jwt.getClaim("scp");
        if (scp instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(new SimpleGrantedAuthority("SCOPE_" + o.toString().trim()));
                }
            }
        }
        return out;
    }

    private static Collection<? extends GrantedAuthority> roleAuthorities(Jwt jwt) {
        Object raw = jwt.getClaim("mikos_roles");
        List<String> roles = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    roles.add(o.toString());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            roles.add(s);
        }
        var mapped = PlatformRoleMapper.mapHighest(roles);
        if (mapped == null) {
            return List.of();
        }
        return PlatformRoleMapper.asAuthorityNames(mapped).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
