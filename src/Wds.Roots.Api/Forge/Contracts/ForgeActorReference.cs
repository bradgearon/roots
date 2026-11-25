using System;

namespace Wds.Roots.Api.Forge.Contracts
{
    /// <summary>
    /// Stable identity that can cross Forge process/service boundaries without copying an actor's full profile.
    /// </summary>
    public sealed class ForgeActorReference
    {
        public string ForgeId { get; set; } = "roots";
        public string ActorId { get; set; } = string.Empty;
        public string ActorType { get; set; } = string.Empty;

        public static ForgeActorReference ForActor(string actorId, string? actorType = null)
        {
            return new ForgeActorReference
            {
                ForgeId = "roots",
                ActorId = actorId ?? string.Empty,
                ActorType = string.IsNullOrWhiteSpace(actorType) ? "person" : actorType!.Trim()
            };
        }

        public override string ToString()
        {
            return "forge://" + (string.IsNullOrWhiteSpace(ForgeId) ? "roots" : ForgeId.Trim())
                + "/actors/" + (ActorId ?? string.Empty);
        }
    }
}
