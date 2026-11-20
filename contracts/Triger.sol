// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract Triger {
    address public owner;

    struct Validator {
        string simHash;
        bool isRegistered;
    }

    mapping(address => Validator) public validators;
    mapping(address => uint256) public rewards;

    event ValidatorRegistered(address indexed validator, string simHash);
    event ValidatorRewarded(address indexed validator, uint256 amount);

    constructor() {
        owner = msg.sender;
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "Not authorized");
        _;
    }

    function registerValidator(string memory simHash) external {
        require(!validators[msg.sender].isRegistered, "Validator already registered");
        require(bytes(simHash).length > 0, "SIM hash cannot be empty");
        validators[msg.sender] = Validator(simHash, true);
        emit ValidatorRegistered(msg.sender, simHash);
    }

    function rewardValidator(address validator, uint256 amount) external onlyOwner {
        require(validators[validator].isRegistered, "Validator not registered");
        rewards[validator] += amount;
        emit ValidatorRewarded(validator, amount);
    }

    function getRewardBalance(address validator) external view returns (uint256) {
        return rewards[validator];
    }

    function isValidator(address account) external view returns (bool) {
        return validators[account].isRegistered;
    }
}
