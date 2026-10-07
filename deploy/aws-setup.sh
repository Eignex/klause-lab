#!/usr/bin/env bash
# One-time AWS setup for running experiments with "host": "aws". Run it once, from a machine with an admin AWS session
# (`aws login`) that can ssh to the lab Mac:
#   deploy/aws-setup.sh [user@lab-host]          default rasmusros@192.168.50.104
# Settings: AWS_REGION (default eu-north-1), INSTANCE_TYPE (default c7i.2xlarge), MAX_INSTANCES (default 5),
# ADMIN_PROFILE (default: the CLI's default profile).
#
# It creates, or reuses when they exist:
#   - an SSH key pair `klause-lab`, its private key installed on the lab Mac;
#   - a security group `klause-lab` in the default VPC that admits SSH only from the lab Mac's public address;
#   - an IAM user `klause-lab` whose only rights are to launch tagged instances of INSTANCE_TYPE with that key and group,
#     tag them at launch, terminate instances tagged klause-lab, read the public Ubuntu image id, and admit the Mac's
#     current address to the group (its address can change); its access key goes into the Mac's `klause-lab` profile;
#   - `$LAB_DATA/aws/aws.properties` on the Mac, which the lab reads: no service needs reinstalling.
set -euo pipefail
host="${1:-rasmusros@192.168.50.104}"
region="${AWS_REGION:-eu-north-1}"
type="${INSTANCE_TYPE:-c7i.2xlarge}"
max="${MAX_INSTANCES:-5}"
name=klause-lab
admin=(aws --region "$region" --output text ${ADMIN_PROFILE:+--profile "$ADMIN_PROFILE"})
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

account="$("${admin[@]}" sts get-caller-identity --query Account)"
echo "account $account, region $region, $type ×$max"
[[ "$("${admin[@]}" ec2 describe-instance-type-offerings --filters "Name=instance-type,Values=$type" \
  --query 'InstanceTypeOfferings[0].InstanceType')" == "$type" ]] || { echo "$type is not offered in $region"; exit 1; }

# The lab Mac needs the AWS CLI it will launch instances with.
ssh "$host" 'export PATH=/opt/homebrew/bin:$PATH; command -v aws >/dev/null || brew install awscli >/dev/null'
mac_ip="$(ssh "$host" 'curl -fsS https://checkip.amazonaws.com')"
echo "lab Mac's public address: $mac_ip"

# Key pair: a fresh one whenever the Mac does not hold the private key of the existing one.
if ssh "$host" "test -f ~/klause-lab-data/aws/$name.pem" && "${admin[@]}" ec2 describe-key-pairs --key-names "$name" >/dev/null 2>&1; then
  echo "key pair $name: kept"
else
  "${admin[@]}" ec2 delete-key-pair --key-name "$name" >/dev/null 2>&1 || true
  "${admin[@]}" ec2 create-key-pair --key-name "$name" --key-type ed25519 --query KeyMaterial > "$work/$name.pem"
  ssh "$host" 'mkdir -p ~/klause-lab-data/aws && chmod 700 ~/klause-lab-data/aws'
  scp -q "$work/$name.pem" "$host:klause-lab-data/aws/$name.pem"
  ssh "$host" "chmod 600 ~/klause-lab-data/aws/$name.pem"
  echo "key pair $name: created, private key on the Mac"
fi

# Security group in the default VPC, SSH from the Mac only.
vpc="$("${admin[@]}" ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId')"
sg="$("${admin[@]}" ec2 describe-security-groups --filters "Name=group-name,Values=$name" "Name=vpc-id,Values=$vpc" \
  --query 'SecurityGroups[0].GroupId')"
if [[ "$sg" == "None" ]]; then
  sg="$("${admin[@]}" ec2 create-security-group --group-name "$name" --vpc-id "$vpc" \
    --description "SSH from the klause lab Mac" --query GroupId)"
fi
"${admin[@]}" ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 22 --cidr "$mac_ip/32" \
  >/dev/null 2>&1 || true
echo "security group $sg"

# IAM user with the narrowest policy that lets the lab do its job.
cat > "$work/policy.json" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "Describe", "Effect": "Allow", "Resource": "*",
     "Action": ["ec2:DescribeInstances", "ec2:DescribeImages", "ec2:DescribeInstanceStatus", "ec2:DescribeSubnets",
                "ec2:DescribeSecurityGroups", "ec2:DescribeKeyPairs"]},
    {"Sid": "LaunchTaggedInstances", "Effect": "Allow", "Action": "ec2:RunInstances",
     "Resource": "arn:aws:ec2:$region:$account:instance/*",
     "Condition": {"StringEquals": {"aws:RequestTag/$name": "true", "ec2:InstanceType": "$type"}}},
    {"Sid": "LaunchTaggedVolumes", "Effect": "Allow", "Action": "ec2:RunInstances",
     "Resource": "arn:aws:ec2:$region:$account:volume/*",
     "Condition": {"StringEquals": {"aws:RequestTag/$name": "true"}}},
    {"Sid": "LaunchWith", "Effect": "Allow", "Action": "ec2:RunInstances",
     "Resource": ["arn:aws:ec2:$region::image/*", "arn:aws:ec2:$region:$account:subnet/*",
                  "arn:aws:ec2:$region:$account:network-interface/*", "arn:aws:ec2:$region:$account:security-group/$sg",
                  "arn:aws:ec2:$region:$account:key-pair/$name"]},
    {"Sid": "TagOnLaunch", "Effect": "Allow", "Action": "ec2:CreateTags", "Resource": "*",
     "Condition": {"StringEquals": {"ec2:CreateAction": "RunInstances"}}},
    {"Sid": "TerminateOwn", "Effect": "Allow", "Action": "ec2:TerminateInstances",
     "Resource": "arn:aws:ec2:$region:$account:instance/*",
     "Condition": {"StringEquals": {"aws:ResourceTag/$name": "true"}}},
    {"Sid": "UbuntuImage", "Effect": "Allow", "Action": "ssm:GetParameter",
     "Resource": "arn:aws:ssm:$region::parameter/aws/service/canonical/*"},
    {"Sid": "AdmitTheMac", "Effect": "Allow", "Action": "ec2:AuthorizeSecurityGroupIngress",
     "Resource": "arn:aws:ec2:$region:$account:security-group/$sg"}
  ]
}
EOF
"${admin[@]}" iam get-user --user-name "$name" >/dev/null 2>&1 || "${admin[@]}" iam create-user --user-name "$name" >/dev/null
"${admin[@]}" iam put-user-policy --user-name "$name" --policy-name "$name-ec2" --policy-document "file://$work/policy.json"
for old in $("${admin[@]}" iam list-access-keys --user-name "$name" --query 'AccessKeyMetadata[].AccessKeyId'); do
  "${admin[@]}" iam delete-access-key --user-name "$name" --access-key-id "$old"
done
read -r key secret < <("${admin[@]}" iam create-access-key --user-name "$name" \
  --query 'AccessKey.[AccessKeyId,SecretAccessKey]')
echo "IAM user $name: policy set, new access key $key"

# The Mac's side: the profile the lab's AWS CLI uses, and the lab's settings.
ssh "$host" "export PATH=/opt/homebrew/bin:\$PATH; aws configure set aws_access_key_id '$key' --profile $name && \
  aws configure set aws_secret_access_key '$secret' --profile $name && aws configure set region '$region' --profile $name"
ssh "$host" "cat > ~/klause-lab-data/aws/aws.properties" <<EOF
region=$region
profile=$name
instanceType=$type
maxInstances=$max
keyName=$name
keyFile=/Users/$(ssh "$host" whoami)/klause-lab-data/aws/$name.pem
securityGroup=$sg
EOF
echo "wrote ~/klause-lab-data/aws/aws.properties on $host; restart the lab (lab update) to start the AWS worker"
