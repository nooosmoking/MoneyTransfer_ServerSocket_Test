package com.example.services;

import com.example.exceptions.NoSuchUserException;
import com.example.exceptions.UserAlreadyExistsException;
import com.example.models.SigninRequest;
import com.example.models.SignupRequest;

import javax.naming.AuthenticationException;

public interface AuthService {
    String signUp(SignupRequest signinRequest) throws UserAlreadyExistsException;

    String signIn(SigninRequest signupRequest) throws NoSuchUserException, AuthenticationException, javax.security.sasl.AuthenticationException;

}
