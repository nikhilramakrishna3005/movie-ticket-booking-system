# movie-ticket-booking-system
Concurrent movie ticket booking platform built with Spring Boot, PostgreSQL, and React.

## Demo accounts (local only)

> **Local assessment use only.** These accounts and passwords are fictional and intentionally
> public. They are seeded by `backend/src/main/resources/db/migration/V8__demo_users.sql` and
> must never be used in a real deployment.

| Username | Password | Role | Assigned theater |
|---|---|---|---|
| `alice` | `alice-demo-pass` | Customer | — |
| `bob` | `bob-demo-pass` | Customer | — |
| `admin.dallas` | `dallas-admin-demo` | Theater admin | Starlight Cinemas Downtown |
| `admin.houston` | `houston-admin-demo` | Theater admin | Bayou Picture House |

The API uses stateless HTTP Basic authentication; there is no signup, session, or token. Basic
sends credentials with every request, so a real deployment would require HTTPS. Example:

```bash
curl -u alice:alice-demo-pass http://localhost:8080/api/auth/me
```

`/actuator/health` is public. Everything under `/api/**` requires authentication, and
`/api/admin/**` additionally requires the theater-admin role.
