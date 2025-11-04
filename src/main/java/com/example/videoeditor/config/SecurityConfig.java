package com.example.videoeditor.config;
import com.example.videoeditor.security.JwtFilter;
import com.example.videoeditor.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


import java.util.List;


@Configuration
public class SecurityConfig implements WebMvcConfigurer {
    private final JwtUtil jwtUtil;


    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "https://splendid-mooncake-2c2f66.netlify.app", "https://scenith.in"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", config);
        return source;
    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(List.of("http://localhost:3000", "https://splendid-mooncake-2c2f66.netlify.app"));
                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
                    config.setExposedHeaders(List.of("Authorization"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/emails/**").permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/api/global-elements", "/api/global-elements/**").permitAll() // Public access
                        .requestMatchers("/projects/{projectId}/waveforms/{filename}").permitAll()
                        .requestMatchers("/audio/sole_tts/{userId}/{filename}").permitAll()
                        .requestMatchers("/developer/**").authenticated() // Requires JWT, role checked in JwtFilter
                        .requestMatchers("/videos/upload", "/videos/my-videos", "/videos/merge", "/videos/edited-videos","/videos/trim", "/videos/split", "/videos/duration/**").authenticated()
                        .requestMatchers("videos/filtered/{userId}/original/{filename}").permitAll()
                        .requestMatchers("/speed-videos/{userId}/{fileName}").permitAll()
                        .requestMatchers("/projects/{projectId}/images/{filename}").permitAll() // Place this BEFORE the general /projects/** rule
                        .requestMatchers("image/projects/{projectId}/{filename}").permitAll() // Place this BEFORE the general /projects/** rule
                        .requestMatchers("image/standalone/{userId}/original/{filename}").permitAll()
                        .requestMatchers("image/standalone/{userId}/processed/{filename}").permitAll()
                        .requestMatchers("elements/{filename}").permitAll() // Place this BEFORE the general /projects/** rule
                        .requestMatchers("/projects/{projectId}/audio/{filename}").permitAll()
                        .requestMatchers("audio/projects/{projectId}/{filename}").permitAll()
                        .requestMatchers("audio/projects/{projectId}/extracted/{filename}").permitAll()
                        .requestMatchers("/projects/{projectId}/videos/{filename}").permitAll()
                        .requestMatchers("videos/projects/{projectId}/{filename}").permitAll()
                        .requestMatchers("subtitles/{userId}/original/{filename}").permitAll()
                        .requestMatchers("aspect_ratio/{userId}/original/{filename}").permitAll()
                        .requestMatchers("image_editor/{userId}/assets/{filename}").permitAll()
                        .requestMatchers("/projects/**", "/projects/{projectId}/add-to-timeline").authenticated()
                        .requestMatchers(HttpMethod.GET, "/videos/edited-videos/**").permitAll()
                        .requestMatchers("/videos/**", "/videos/*").permitAll()  // ✅ Allow public video access
                        .anyRequest().authenticated()
                )
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);


        return http.build();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:3000", "https://splendid-mooncake-2c2f66.netlify.app")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }


    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
