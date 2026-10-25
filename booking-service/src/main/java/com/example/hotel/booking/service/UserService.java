package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.CreateUserRequest;
import com.example.hotel.booking.controller.dto.UpdateUserRequest;
import com.example.hotel.booking.controller.dto.UserPageResponse;
import com.example.hotel.booking.controller.dto.UserResponse;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.service.exception.EmptyUserUpdateException;
import com.example.hotel.booking.service.exception.UserNotFoundException;
import com.example.hotel.booking.service.exception.UsernameAlreadyExistsException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import lombok.RequiredArgsConstructor;

import static java.util.Locale.ROOT;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        final var passwordHash = passwordEncoder.encode(request.password());
        final var user = new User(request.username(), passwordHash, request.role());
        saveUser(user);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserPageResponse listUsers(int page, int size) {
        final var totalElements = userRepository.count();
        final var pageable = PageRequest.of(page, size, Sort.by("id"));
        if (pageable.getOffset() >= totalElements) {
            return new UserPageResponse(List.of(), page, size, totalElements);
        }

        final var users = userRepository.findAllBy(pageable).stream()
            .map(UserService::toResponse)
            .toList();
        return new UserPageResponse(users, page, size, totalElements);
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(long id) {
        return toResponse(findUser(id));
    }

    @Transactional
    public UserResponse updateUser(long id, UpdateUserRequest request) {
        if (request.getUsername() == null
            && request.getPassword() == null
            && request.getRole() == null) {
            throw new EmptyUserUpdateException();
        }

        final var user = findUser(id);
        final var username = request.getUsername() == null
            ? user.getUsername()
            : request.getUsername();
        final var passwordHash = request.getPassword() == null
            ? user.getPasswordHash()
            : passwordEncoder.encode(request.getPassword());
        final var role = request.getRole() == null
            ? user.getRole()
            : request.getRole();

        user.updateDetails(username, passwordHash, role);
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw evaluatePersistenceException(exception);
        }
        return toResponse(user);
    }

    @Transactional
    public void deleteUser(long id) {
        userRepository.delete(findUser(id));
    }

    private User findUser(long id) {
        return userRepository.findById(id)
            .orElseThrow(UserNotFoundException::new);
    }

    private void saveUser(User user) {
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw evaluatePersistenceException(exception);
        }
    }

    private static RuntimeException evaluatePersistenceException(DataIntegrityViolationException exception) {
        if (exception.getCause() instanceof ConstraintViolationException constraint
            && constraint.getConstraintName() != null
            && constraint.getConstraintName().toLowerCase(ROOT).contains("uk_users_username")) {
            return new UsernameAlreadyExistsException(exception);
        }
        return exception;
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
