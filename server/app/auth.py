"""Bearer-token authentication.

The token is a shared secret. We compare in constant time to avoid leaking
information through timing, and we never log the token itself.
"""

from __future__ import annotations

import secrets

from fastapi import Header, HTTPException, status


def make_auth_dependency(expected_token: str):
    """Return a FastAPI dependency that validates the Authorization header."""

    async def verify(authorization: str | None = Header(default=None)) -> None:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Missing bearer token",
                headers={"WWW-Authenticate": "Bearer"},
            )
        presented = authorization[len("Bearer ") :].strip()
        # secrets.compare_digest is constant-time for equal-length inputs and
        # safely handles differing lengths.
        if not secrets.compare_digest(presented, expected_token):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Invalid token",
            )

    return verify
